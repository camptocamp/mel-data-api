package com.camptocamp.opendata.processor.geotools;

import java.util.function.Function;

import javax.annotation.Nullable;

import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.locationtech.jts.geom.Geometry;

import com.camptocamp.opendata.model.GeodataRecord;
import com.camptocamp.opendata.model.GeometryProperty;

import lombok.NonNull;

/**
 * Imperative (blocking) function that reprojects the geometry property of a
 * {@link GeodataRecord} to the coordinate reference system given at the
 * function's constructor, and returns a new {@link GeodataRecord} with the
 * reprojected geometry and the same other properties than the original record.
 *
 */
class RecordReprojectFunction implements Function<GeodataRecord, GeodataRecord> {

    private final GeometryReprojectFunction reproject;
    private final String targetSrs;

    /**
     * @param targetSrs the coordinate reference system identifier to reproject to
     * @throws IllegalArgumentException if {@code targetSrs} cannot be parsed as a
     *                                  {@link CoordinateReferenceSystem}
     */
    public RecordReprojectFunction(@NonNull String targetSrs) {
        this.targetSrs = targetSrs;
        this.reproject = new GeometryReprojectFunction(targetSrs);
    }

    @Override
    public @Nullable GeodataRecord apply(@Nullable GeodataRecord source) {
        if (source == null || source.getGeometry() == null) {
            return source;
        }
        GeometryProperty orig = source.getGeometry();
        Geometry reprojected = reproject(orig.getValue(), orig.getSrs());
        GeometryProperty reprojectedProp = orig.withValue(reprojected).withSrs(targetSrs);
        return source.withGeometry(reprojectedProp);
    }

    private Geometry reproject(Geometry value, String sourceSrs) {
        return reproject.apply(value, sourceSrs);
    }

}
