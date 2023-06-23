/*
 * Copyright (C) 2023 B3Partners B.V.
 *
 * SPDX-License-Identifier: MIT
 */
package nl.b3p.tailormap.api.controller;

import io.micrometer.core.annotation.Counted;
import io.micrometer.core.annotation.Timed;
import java.io.IOException;
import java.io.Serializable;
import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import nl.b3p.tailormap.api.annotation.AppRestController;
import nl.b3p.tailormap.api.geotools.featuresources.FeatureSourceFactoryHelper;
import nl.b3p.tailormap.api.geotools.processing.GeometryProcessor;
import nl.b3p.tailormap.api.persistence.GeoService;
import nl.b3p.tailormap.api.persistence.TMFeatureType;
import nl.b3p.tailormap.api.persistence.helper.TMAttributeTypeHelper;
import nl.b3p.tailormap.api.persistence.json.AppTreeLayerNode;
import nl.b3p.tailormap.api.persistence.json.GeoServiceLayer;
import nl.b3p.tailormap.api.persistence.json.TMAttributeDescriptor;
import nl.b3p.tailormap.api.repository.FeatureSourceRepository;
import nl.b3p.tailormap.api.util.Constants;
import nl.b3p.tailormap.api.viewer.model.Feature;
import org.geotools.data.DataUtilities;
import org.geotools.data.DefaultTransaction;
import org.geotools.data.FeatureStore;
import org.geotools.data.Transaction;
import org.geotools.data.simple.SimpleFeatureIterator;
import org.geotools.data.simple.SimpleFeatureSource;
import org.geotools.data.simple.SimpleFeatureStore;
import org.geotools.factory.CommonFactoryFinder;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.util.factory.GeoTools;
import org.geotools.util.factory.Hints;
import org.locationtech.jts.geom.Geometry;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.type.AttributeDescriptor;
import org.opengis.filter.Filter;
import org.opengis.filter.FilterFactory2;
import org.opengis.filter.identity.FeatureId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;

@AppRestController
@Validated
@RequestMapping(
    path = {"${tailormap-api.base-path}/{viewerKind}/{viewerName}/layer/{appLayerId}/edit/feature"})
public class EditFeatureController implements Constants {
  private static final Logger logger =
      LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private final FeatureSourceFactoryHelper featureSourceFactoryHelper;
  private final FilterFactory2 ff =
      CommonFactoryFinder.getFilterFactory2(GeoTools.getDefaultHints());
  private final FeatureSourceRepository featureSourceRepository;

  public EditFeatureController(
      FeatureSourceFactoryHelper featureSourceFactoryHelper,
      FeatureSourceRepository featureSourceRepository) {
    this.featureSourceFactoryHelper = featureSourceFactoryHelper;
    this.featureSourceRepository = featureSourceRepository;
  }

  @Transactional
  @PostMapping(
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE)
  @Timed(value = "create_feature", description = "time spent to process create feature call")
  @Counted(value = "create_feature", description = "number of create feature calls")
  public ResponseEntity<Serializable> createFeature(
      @ModelAttribute AppTreeLayerNode appTreeLayerNode,
      @ModelAttribute GeoService service,
      @ModelAttribute GeoServiceLayer layer,
      @RequestBody Feature completeFeature) {

    checkAuthentication();

    TMFeatureType tmFeatureType = getFeatureType(appTreeLayerNode, service, layer);

    Map<String, Object> attributesMap = completeFeature.getAttributes();
    Set<String> attributeNames =
        tmFeatureType.getAttributes().stream()
            .map(TMAttributeDescriptor::getName)
            .collect(Collectors.toSet());

    if (!attributeNames.containsAll(completeFeature.getAttributes().keySet())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Feature cannot be edited, one or more requested attributes are not available on the feature type");
    }

    Feature newFeature;
    SimpleFeatureSource fs = null;

    try (Transaction transaction = new DefaultTransaction("create")) {
      fs = featureSourceFactoryHelper.openGeoToolsFeatureSource(tmFeatureType);

      SimpleFeature simpleFeature;
      SimpleFeatureBuilder simpleFeatureBuilder = new SimpleFeatureBuilder(fs.getSchema());
      if (null != completeFeature.getFid() && !completeFeature.getFid().isEmpty()) {
        simpleFeature = simpleFeatureBuilder.buildFeature(completeFeature.getFid());
        simpleFeature.getUserData().put(Hints.USE_PROVIDED_FID, Boolean.TRUE);
      } else {
        simpleFeature = simpleFeatureBuilder.buildFeature(null);
      }

      handleGeometryAttributes(tmFeatureType, completeFeature, attributesMap);
      for (Map.Entry<String, Object> entry : attributesMap.entrySet()) {
        simpleFeature.setAttribute(entry.getKey(), entry.getValue());
      }

      if (fs instanceof SimpleFeatureStore) {
        ((SimpleFeatureStore) fs).setTransaction(transaction);
        List<FeatureId> newFids =
            ((SimpleFeatureStore) fs).addFeatures(DataUtilities.collection(simpleFeature));

        transaction.commit();
        // find the created feature to return
        newFeature = getFeature(fs, ff.id(newFids.get(0)));
      } else {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "Feature cannot be added, datasource is not editable");
      }
    } catch (RuntimeException | IOException e) {
      // either opening datastore, modify or transaction failed
      logger.error("Error creating new feature {}", completeFeature, e);
      String message = e.getMessage();
      if (null != e.getCause() && null != e.getCause().getMessage()) {
        message = e.getCause().getMessage();
      }
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, message, e);
    } finally {
      if (fs != null) {
        fs.getDataStore().dispose();
      }
    }
    return new ResponseEntity<>(newFeature, HttpStatus.OK);
  }

  @Transactional
  @PatchMapping(
      consumes = MediaType.APPLICATION_JSON_VALUE,
      produces = MediaType.APPLICATION_JSON_VALUE,
      path = "/{fid}")
  @Timed(value = "update_feature", description = "time spent to process patch feature call")
  @Counted(value = "update_feature", description = "number of patch feature calls")
  public ResponseEntity<Serializable> patchFeature(
      @ModelAttribute AppTreeLayerNode appTreeLayerNode,
      @ModelAttribute GeoService service,
      @ModelAttribute GeoServiceLayer layer,
      @PathVariable String fid,
      @RequestBody Feature partialFeature) {

    checkAuthentication();

    TMFeatureType tmFeatureType = getFeatureType(appTreeLayerNode, service, layer);

    Map<String, Object> attributesMap = partialFeature.getAttributes();
    Set<String> attributeNames =
        tmFeatureType.getAttributes().stream()
            .map(TMAttributeDescriptor::getName)
            .collect(Collectors.toSet());

    if (!attributeNames.containsAll(partialFeature.getAttributes().keySet())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Feature cannot be edited, one or more requested attributes are not available on the feature type");
    }

    handleGeometryAttributes(tmFeatureType, partialFeature, attributesMap);

    Feature patchedFeature;
    SimpleFeatureSource fs = null;
    try (Transaction transaction = new DefaultTransaction("edit")) {
      fs = featureSourceFactoryHelper.openGeoToolsFeatureSource(tmFeatureType);
      // find feature to update
      final Filter filter = ff.id(ff.featureId(fid));
      if (!fs.getFeatures(filter).isEmpty() && fs instanceof SimpleFeatureStore) {
        // NOTE geotools does not report back that the feature was updated, no error === success
        ((SimpleFeatureStore) fs).setTransaction(transaction);
        ((SimpleFeatureStore) fs)
            .modifyFeatures(
                attributesMap.keySet().toArray(new String[] {}),
                attributesMap.values().toArray(),
                filter);
        transaction.commit();
        // find the updated feature to return
        patchedFeature = getFeature(fs, filter);
      } else {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "Feature cannot be edited, it does not exist or is not editable");
      }
    } catch (RuntimeException | IOException e) {
      // either opening datastore, modify or transaction failed
      logger.error("Error patching feature {}", partialFeature, e);
      String message = e.getMessage();
      if (null != e.getCause() && null != e.getCause().getMessage()) {
        message = e.getCause().getMessage();
      }
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, message, e);
    } finally {
      if (fs != null) {
        fs.getDataStore().dispose();
      }
    }
    return new ResponseEntity<>(patchedFeature, HttpStatus.OK);
  }

  @Transactional
  @DeleteMapping(path = "/{fid}")
  @Timed(value = "delete_feature", description = "time spent to process delete feature call")
  @Counted(value = "delete_feature", description = "number of delete feature calls")
  public ResponseEntity<Void> deleteFeature(
      @ModelAttribute AppTreeLayerNode appTreeLayerNode,
      @ModelAttribute GeoService service,
      @ModelAttribute GeoServiceLayer layer,
      @PathVariable String fid) {

    checkAuthentication();

    TMFeatureType tmFeatureType = getFeatureType(appTreeLayerNode, service, layer);

    SimpleFeatureSource fs = null;
    try (Transaction transaction = new DefaultTransaction("delete")) {
      fs = featureSourceFactoryHelper.openGeoToolsFeatureSource(tmFeatureType);
      Filter filter = ff.id(ff.featureId(fid));
      if (fs instanceof FeatureStore) {
        // NOTE geotools does not report back that the feature does not exist, nor the number of
        // deleted features, no error === success
        ((FeatureStore<?, ?>) fs).setTransaction(transaction);
        ((FeatureStore<?, ?>) fs).removeFeatures(filter);
        transaction.commit();
      } else {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Layer cannot be edited");
      }
    } catch (IOException e) {
      // either opening datastore or commit failed
      logger.error("Error delting feature {}", fid, e);
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
    } finally {
      if (fs != null) {
        fs.getDataStore().dispose();
      }
    }

    return new ResponseEntity<>(HttpStatus.NO_CONTENT);
  }

  private void checkAuthentication() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    boolean isAuthenticated =
        authentication != null && !(authentication instanceof AnonymousAuthenticationToken);
    if (!isAuthenticated) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
    }
  }

  private TMFeatureType getFeatureType(
      AppTreeLayerNode appTreeLayerNode, GeoService service, GeoServiceLayer layer) {
    if (null == layer) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "Cannot find layer " + appTreeLayerNode);
    }

    TMFeatureType tmft = service.findFeatureTypeForLayer(layer, featureSourceRepository);
    if (null == tmft) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Layer does not have feature type");
    }
    return tmft;
  }

  private Feature getFeature(SimpleFeatureSource fs, Filter filter) throws IOException {
    Feature modelFeature = null;
    try (SimpleFeatureIterator feats = fs.getFeatures(filter).features()) {
      if (feats.hasNext()) {
        SimpleFeature simpleFeature = feats.next();
        modelFeature =
            new Feature()
                .geometry(
                    GeometryProcessor.processGeometry(
                        (simpleFeature.getDefaultGeometry()), false, null))
                .fid(simpleFeature.getID());
        for (AttributeDescriptor att : simpleFeature.getFeatureType().getAttributeDescriptors()) {
          Object value = simpleFeature.getAttribute(att.getName());
          if (value instanceof Geometry) {
            value = GeometryProcessor.geometryToWKT((Geometry) value);
          }
          modelFeature.putAttributesItem(att.getLocalName(), value);
        }
      }
    }
    return modelFeature;
  }

  /** Handle geometry attributes, essentially this is a WKT to Geometry conversion. */
  private void handleGeometryAttributes(
      TMFeatureType tmFeatureType, Feature modelFeature, Map<String, Object> attributesMap) {
    tmFeatureType.getAttributes().stream()
        .filter(attr -> TMAttributeTypeHelper.isGeometry(attr.getType()))
        .filter(attr -> modelFeature.getAttributes().containsKey(attr.getName()))
        .forEach(
            attr -> {
              Geometry geometry =
                  GeometryProcessor.wktToGeometry(
                      (String) modelFeature.getAttributes().get(attr.getName()));
              attributesMap.put(attr.getName(), geometry);
            });
  }
}
