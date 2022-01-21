/*
 * Copyright (C) 2022 B3Partners B.V.
 *
 * SPDX-License-Identifier: MIT
 */
package nl.b3p.tailormap.api.geotools.processing;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.precision.GeometryPrecisionReducer;
import org.locationtech.jts.simplify.TopologyPreservingSimplifier;

import java.nio.charset.StandardCharsets;

import javax.validation.constraints.NotNull;

/**
 * Utility functions on feature geometries.
 *
 * @author mprins
 * @since 0.1
 */
public final class GeometryProcessor {
    private static final Log LOG = LogFactory.getLog(GeometryProcessor.class);

    private GeometryProcessor() {}

    /**
     * process the geometry into a (optionally simplified) string representation.
     *
     * @param geometry An object representing a geometry
     * @param simplfyGeometry set to {@code true} to simplify
     * @return the string representation of the argument - normally WKT, optionally simplified
     */
    @NotNull
    public static String processGeometry(
            @NotNull final Object geometry, @NotNull final Boolean simplfyGeometry) {
        if (simplfyGeometry && Geometry.class.isAssignableFrom(geometry.getClass())) {
            return simplify((Geometry) geometry);
        }
        // return WKT
        return geometry.toString();
    }

    /**
     * Simplifies given geometry to reduce (transfer) size, start off with 1 and each iteration
     * multiply with 10, max 4 steps, so [1, 10, 100, 1000], if the geomeomtry is still too large
     * bail out and use bbox. TODO this works for CRS in meters, may not work for degrees
     *
     * @param geom gemetry to simplify
     * @return simplified geometry as WKT string
     */
    @NotNull
    private static String simplify(@NotNull Geometry geom) {
        final int megabytes = (2097152 /* 2MB is the default tomcat max post size */ - 100 * 1024);

        LOG.debug(geom.getPrecisionModel().getScale());
        PrecisionModel pm = new PrecisionModel(geom.getPrecisionModel());
        GeometryPrecisionReducer gpr = new GeometryPrecisionReducer(pm);
        geom = gpr.reduce(geom);
        Geometry bbox = geom.getEnvelope();

        double distanceTolerance = 1.0;
        String geomTxt = geom.toText();

        while ((geomTxt.getBytes(StandardCharsets.UTF_8).length > megabytes
                        || geom.getCoordinates().length > 600)
                && distanceTolerance < 9999) {
            LOG.debug("Simplify selected feature geometry with distance of: " + distanceTolerance);
            geom = TopologyPreservingSimplifier.simplify(geom, distanceTolerance);
            geom = gpr.reduce(geom);
            geomTxt = geom.toText();
            distanceTolerance = 10 * distanceTolerance;
        }

        if (distanceTolerance > 9999) {
            LOG.debug("Maximum number of simplify cycles reached, returning bounding box instead.");
            return bbox.toText();
        } else {
            return geomTxt;
        }
    }
}
