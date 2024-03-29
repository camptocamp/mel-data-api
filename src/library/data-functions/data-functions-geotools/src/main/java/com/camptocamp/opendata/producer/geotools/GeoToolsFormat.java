package com.camptocamp.opendata.producer.geotools;

import static java.util.Spliterator.DISTINCT;
import static java.util.Spliterator.IMMUTABLE;
import static java.util.Spliterator.NONNULL;
import static java.util.Spliterators.spliteratorUnknownSize;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Spliterator;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.geotools.api.data.DataStore;
import org.geotools.api.data.DataStoreFactorySpi;
import org.geotools.api.data.DataStoreFinder;
import org.geotools.api.data.FeatureReader;
import org.geotools.api.data.Query;
import org.geotools.api.data.SimpleFeatureSource;
import org.geotools.api.data.Transaction;
import org.geotools.api.feature.Feature;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.api.feature.type.FeatureType;
import org.geotools.feature.FeatureReaderIterator;

import com.camptocamp.opendata.model.DataQuery;
import com.camptocamp.opendata.model.GeodataRecord;
import com.google.common.base.Stopwatch;
import com.google.common.collect.Iterators;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

@Slf4j(topic = "com.camptocamp.opendata.producer.geotools")
abstract class GeoToolsFormat {

    public abstract String getName();

    public abstract boolean canHandle(@NonNull URI datasetUri);

    protected abstract Map<String, ?> toConnectionParams(@NonNull DataQuery query);

    public Stream<GeodataRecord> read(@NonNull DataQuery query) {
        try {
            final DataStore dataStore = resolveDataStore(query);
            final List<String> typeNames = resolveTypeNames(dataStore, query.getLayerName());

            Stream<GeodataRecord> data = typeNames.stream()
                    .flatMap(typeName -> readFeatureType(dataStore, query.withLayerName(typeName)));
            data = data.onClose(() -> dispose(dataStore, query.getSource().getUri()));
            return data;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public long count(@NonNull DataQuery query) {
        Objects.requireNonNull(query.getLayerName(), "layerName");
        try {
            final DataStore dataStore = resolveDataStore(query);
            Query gtQuery = asGeoToolsQuery(query);
            try {
                SimpleFeatureSource fs = dataStore.getFeatureSource(query.getLayerName());
                return fs.getCount(gtQuery);
            } finally {
                dispose(dataStore, query.getSource().getUri());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Query asGeoToolsQuery(DataQuery query) {
        Query gtQuery = new Query(query.getLayerName());
        if (null != query.getOffset())
            gtQuery.setStartIndex(query.getOffset());
        if (null != query.getLimit())
            gtQuery.setMaxFeatures(query.getLimit());
        return gtQuery;
    }

    protected Stream<GeodataRecord> readFeatureType(final @NonNull DataStore store, final @NonNull DataQuery query) {
        String typeName = query.getLayerName();
        log.debug("Getting FeatureReader for {}", typeName);
        FeatureReader<SimpleFeatureType, SimpleFeature> reader;
        try {
            Query gtQuery = asGeoToolsQuery(query);
            Stopwatch sw = Stopwatch.createStarted();
            reader = store.getFeatureReader(gtQuery, Transaction.AUTO_COMMIT);
            log.debug("FeatureReader obtained for {} in {}", typeName, sw.stop());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }

        Stream<SimpleFeature> features = readerToStream(reader, typeName, query.getSource().getUri());
        Stream<GeodataRecord> records = features.map(new FeatureToRecord());
        return records;
    }

    protected Stream<SimpleFeature> readerToStream(FeatureReader<SimpleFeatureType, SimpleFeature> reader,
            @NonNull String typeName, @NonNull URI uri) {

        Iterator<SimpleFeature> asIterator = new FeatureReaderIterator<SimpleFeature>(reader);
        final int spliteratorType = DISTINCT | IMMUTABLE | NONNULL;
        Spliterator<SimpleFeature> asSpliterator = spliteratorUnknownSize(asIterator, spliteratorType);
        final boolean parallel = false;

        Stream<SimpleFeature> stream = StreamSupport.stream(asSpliterator, parallel);
        stream = stream.onClose(() -> close(reader, typeName, uri));
        return stream;
    }

    /**
     * @param reader   the reader to close
     * @param typeName just for logging, required because
     *                 {@link FeatureReader#getFeatureType()} returns {@code null},
     *                 which seems like a bug in geotools
     * @param uri      data source URI, for logging purposes
     */
    private void close(FeatureReader<? extends FeatureType, ? extends Feature> reader, @NonNull String typeName,
            @NonNull URI uri) {
        log.debug("Closing feature reader {} from {}", typeName, uri);
        try {
            reader.close();
        } catch (IOException e) {
            log.warn("Error closing feature reader {}, won't affect flux", typeName, e);
        }
    }

    private void dispose(DataStore dataStore, URI uri) {
        log.debug("Closing DataStore {} for {}", dataStore.getClass().getSimpleName(), uri);
        try {
            dataStore.dispose();
        } catch (RuntimeException e) {
            log.warn("Error closing DataStore {} for {}, won't affect flux", dataStore.getClass().getSimpleName(), uri,
                    e);
        }
    }

    protected List<String> resolveTypeNames(@NonNull DataStore dataStore, String typeName) throws IOException {
        log.debug("Querying available type names");
        List<String> typeNames = Arrays.asList(dataStore.getTypeNames());
        Stopwatch sw = Stopwatch.createStarted();
        if (typeName != null) {
            if (!typeNames.contains(typeName)) {
                throw new IllegalArgumentException("Requested layer " + typeName + " does not exist: " + typeNames);
            }
            log.debug("Resolved requested type name {} out of {} published in {}", typeName, typeNames.size(),
                    sw.stop());
            return Collections.singletonList(typeName);
        }
        log.debug("Resolved {} type names in {}: {}", typeNames.size(), sw.stop(),
                typeNames.stream().collect(Collectors.joining(",")));
        return typeNames;
    }

    protected DataStore resolveDataStore(@NonNull DataQuery query) throws IOException {
        final Map<String, ?> params = toConnectionParams(query);
        if (log.isDebugEnabled()) {
            log.debug("Resolving DataStore for {} with params {}", query.getSource().getUri(),
                    params.keySet().stream().sorted().collect(Collectors.joining(",")));
        }

        Iterator<DataStoreFactorySpi> matchingFactories = Iterators.filter(DataStoreFinder.getAvailableDataStores(),
                fac -> fac.canProcess(params));
        String dataStoreDisplayName = null;
        if (matchingFactories.hasNext()) {
            dataStoreDisplayName = matchingFactories.next().getDisplayName();
        }

        Stopwatch sw = Stopwatch.createStarted();
        DataStore dataStore = DataStoreFinder.getDataStore(params);
        if (null == dataStore) {
            throw new IllegalStateException("Unable to create DataStore for " + query);
        }
        if (dataStoreDisplayName == null) {
            dataStoreDisplayName = dataStore.getClass().getSimpleName();
        }
        log.debug("Got {} DataStore in {} for {}", dataStoreDisplayName, sw.stop(), query.getSource().getUri());
        return dataStore;
    }

    protected URL toURL(@NonNull URI datasetUri) {
        final String scheme = datasetUri.getScheme();
        try {
            if (null == scheme) {
                File file = new File(datasetUri.toString());
                return file.getAbsoluteFile().toURI().toURL();
            }
            if ("file".equals(scheme)) {
                File file = new File(datasetUri);
                return file.getAbsoluteFile().toURI().toURL();
            }
            return datasetUri.toURL();
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException(e);
        }
    }

    public @Override String toString() {
        return getName();
    }
}
