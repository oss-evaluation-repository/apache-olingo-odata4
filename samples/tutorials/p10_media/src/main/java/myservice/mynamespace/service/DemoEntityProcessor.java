/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package myservice.mynamespace.service;

import java.io.InputStream;
import java.util.List;
import java.util.Locale;

import org.apache.olingo.commons.api.data.ContextURL;
import org.apache.olingo.commons.api.data.ContextURL.Suffix;
import org.apache.olingo.commons.api.data.Entity;
import org.apache.olingo.commons.api.edm.EdmEntitySet;
import org.apache.olingo.commons.api.edm.EdmEntityType;
import org.apache.olingo.commons.api.edm.EdmNavigationProperty;
import org.apache.olingo.commons.api.format.ContentType;
import org.apache.olingo.commons.api.http.HttpHeader;
import org.apache.olingo.commons.api.http.HttpMethod;
import org.apache.olingo.commons.api.http.HttpStatusCode;
import org.apache.olingo.server.api.OData;
import org.apache.olingo.server.api.ODataApplicationException;
import org.apache.olingo.server.api.ODataLibraryException;
import org.apache.olingo.server.api.ODataRequest;
import org.apache.olingo.server.api.ODataResponse;
import org.apache.olingo.server.api.ServiceMetadata;
import org.apache.olingo.server.api.deserializer.DeserializerException;
import org.apache.olingo.server.api.deserializer.DeserializerResult;
import org.apache.olingo.server.api.deserializer.ODataDeserializer;
import org.apache.olingo.server.api.processor.EntityProcessor;
import org.apache.olingo.server.api.processor.MediaEntityProcessor;
import org.apache.olingo.server.api.serializer.EntitySerializerOptions;
import org.apache.olingo.server.api.serializer.ODataSerializer;
import org.apache.olingo.server.api.serializer.SerializerException;
import org.apache.olingo.server.api.serializer.SerializerResult;
import org.apache.olingo.server.api.uri.UriInfo;
import org.apache.olingo.server.api.uri.UriParameter;
import org.apache.olingo.server.api.uri.UriResource;
import org.apache.olingo.server.api.uri.UriResourceEntitySet;
import org.apache.olingo.server.api.uri.UriResourceNavigation;

import myservice.mynamespace.data.Storage;
import myservice.mynamespace.util.Util;

public class DemoEntityProcessor implements EntityProcessor, MediaEntityProcessor {

	private OData odata;
	private Storage storage;
	private ServiceMetadata serviceMetadata;

	public DemoEntityProcessor(Storage storage) {
		this.storage = storage;
	}

	public void init(OData odata, ServiceMetadata serviceMetadata) {
		this.odata = odata;
		this.serviceMetadata = serviceMetadata;
	}
  /**
   * This method is invoked when a single entity has to be read.
   * In our example, this can be either a "normal" read operation, or a navigation:
   * 
   * Example for "normal" read operation:
   * http://localhost:8080/DemoService/DemoService.svc/Products(1)
   * 
   * Example for navigation
   * http://localhost:8080/DemoService/DemoService.svc/Products(1)/Category
   */
  public void readEntity(ODataRequest request, ODataResponse response, UriInfo uriInfo, ContentType responseFormat)
      throws ODataApplicationException, SerializerException {
    
    EdmEntityType responseEdmEntityType = null; // we'll need this to build the ContextURL
    Entity responseEntity = null; // required for serialization of the response body
    EdmEntitySet responseEdmEntitySet = null; // we need this for building the contextUrl

    // 1st step: retrieve the requested Entity: can be "normal" read operation, or navigation (to-one)
    List<UriResource> resourceParts = uriInfo.getUriResourceParts();
    int segmentCount = resourceParts.size();
    
    UriResource uriResource = resourceParts.get(0); // in our example, the first segment is the EntitySet
    UriResourceEntitySet uriResourceEntitySet = (UriResourceEntitySet) uriResource;
    EdmEntitySet startEdmEntitySet = uriResourceEntitySet.getEntitySet();

    // Analyze the URI segments
    if (segmentCount == 1) { // no navigation
      responseEdmEntityType = startEdmEntitySet.getEntityType();
      responseEdmEntitySet = startEdmEntitySet; // since we have only one segment

      // 2. step: retrieve the data from backend
      List<UriParameter> keyPredicates = uriResourceEntitySet.getKeyPredicates();
      responseEntity = storage.readEntityData(startEdmEntitySet, keyPredicates);
    } else if (segmentCount == 2) { // navigation
      UriResource navSegment = resourceParts.get(1); // in our example we don't support more complex URIs
      if (navSegment instanceof UriResourceNavigation) {
        UriResourceNavigation uriResourceNavigation = (UriResourceNavigation) navSegment;
        EdmNavigationProperty edmNavigationProperty = uriResourceNavigation.getProperty();
        responseEdmEntityType = edmNavigationProperty.getType();
        // contextURL displays the last segment
        responseEdmEntitySet = Util.getNavigationTargetEntitySet(startEdmEntitySet, edmNavigationProperty);

        // 2nd: fetch the data from backend.
        // e.g. for the URI: Products(1)/Category we have to find the correct Category entity
        List<UriParameter> keyPredicates = uriResourceEntitySet.getKeyPredicates();
        // e.g. for Products(1)/Category we have to find first the Products(1)
        Entity sourceEntity = storage.readEntityData(startEdmEntitySet, keyPredicates);

        // now we have to check if the navigation is
        // a) to-one: e.g. Products(1)/Category
        // b) to-many with key: e.g. Categories(3)/Products(5)
        // the key for nav is used in this case: Categories(3)/Products(5)
        List<UriParameter> navKeyPredicates = uriResourceNavigation.getKeyPredicates();

        if (navKeyPredicates.isEmpty()) { // e.g. DemoService.svc/Products(1)/Category
          responseEntity = storage.getRelatedEntity(sourceEntity, responseEdmEntityType);
        } else { // e.g. DemoService.svc/Categories(3)/Products(5)
          responseEntity = storage.getRelatedEntity(sourceEntity, responseEdmEntityType, navKeyPredicates);
        }
      }
    } else {
      // this would be the case for e.g. Products(1)/Category/Products(1)/Category
      throw new ODataApplicationException("Not supported", HttpStatusCode.NOT_IMPLEMENTED.getStatusCode(), Locale.ROOT);
    }

    if (responseEntity == null) {
      // this is the case for e.g. DemoService.svc/Categories(4) or DemoService.svc/Categories(3)/Products(999)
      throw new ODataApplicationException("Nothing found.", HttpStatusCode.NOT_FOUND.getStatusCode(), Locale.ROOT);
    }

    // 3. serialize
    ContextURL contextUrl = ContextURL.with().entitySet(responseEdmEntitySet).suffix(Suffix.ENTITY).build();
    EntitySerializerOptions opts = EntitySerializerOptions.with().contextURL(contextUrl).build();

    ODataSerializer serializer = odata.createSerializer(responseFormat);
    SerializerResult serializerResult = serializer.entity(serviceMetadata,
        responseEdmEntityType, responseEntity, opts);

    // 4. configure the response object
    response.setContent(serializerResult.getContent());
    response.setStatusCode(HttpStatusCode.OK.getStatusCode());
    response.setHeader(HttpHeader.CONTENT_TYPE, responseFormat.toContentTypeString());
  }

  /*
	 * Example request:
	 * 
	 * POST URL: http://localhost:8080/DemoService/DemoService.svc/Products
	 * Header: Content-Type: application/json;odata.metadata=minimal
	 * Request body:
	 	{
			"ID":3,
			"Name":"Ergo Screen",
			"Description":"17 Optimum Resolution 1024 x 768 @ 85Hz, resolution 1280 x 960"
		}
	 * */
	public void createEntity(ODataRequest request, ODataResponse response, UriInfo uriInfo,
													 ContentType requestFormat, ContentType responseFormat)
				throws ODataApplicationException, DeserializerException, SerializerException {
		
		// 1. Retrieve the entity type from the URI 
		EdmEntitySet edmEntitySet = Util.getEdmEntitySet(uriInfo);
		EdmEntityType edmEntityType = edmEntitySet.getEntityType();

		// 2. create the data in backend 
		// 2.1. retrieve the payload from the POST request for the entity to create and deserialize it
		InputStream requestInputStream = request.getBody();
		ODataDeserializer deserializer = odata.createDeserializer(requestFormat);
		DeserializerResult result = deserializer.entity(requestInputStream, edmEntityType);
		Entity requestEntity = result.getEntity();
		// 2.2 do the creation in backend, which returns the newly created entity
		Entity createdEntity = storage.createEntityData(edmEntitySet, requestEntity);
		
		// 3. serialize the response (we have to return the created entity)
		ContextURL contextUrl = ContextURL.with().entitySet(edmEntitySet).build(); 
		EntitySerializerOptions options = EntitySerializerOptions.with().contextURL(contextUrl).build(); // expand and select currently not supported 
		
		ODataSerializer serializer = odata.createSerializer(responseFormat);
		SerializerResult serializedResponse = serializer.entity(serviceMetadata, edmEntityType, createdEntity, options);
		
		//4. configure the response object
		response.setContent(serializedResponse.getContent());
		response.setStatusCode(HttpStatusCode.CREATED.getStatusCode());
		response.setHeader(HttpHeader.CONTENT_TYPE, responseFormat.toContentTypeString());
	}

	
	public void updateEntity(ODataRequest request, ODataResponse response, UriInfo uriInfo,
                           ContentType requestFormat, ContentType responseFormat)
							throws ODataApplicationException, DeserializerException, SerializerException {
		
		// 1. Retrieve the entity set which belongs to the requested entity 
		List<UriResource> resourcePaths = uriInfo.getUriResourceParts();
		// Note: only in our example we can assume that the first segment is the EntitySet
		UriResourceEntitySet uriResourceEntitySet = (UriResourceEntitySet) resourcePaths.get(0); 
		EdmEntitySet edmEntitySet = uriResourceEntitySet.getEntitySet();
		EdmEntityType edmEntityType = edmEntitySet.getEntityType();

		// 2. update the data in backend
		// 2.1. retrieve the payload from the PUT request for the entity to be updated 
		InputStream requestInputStream = request.getBody();
		ODataDeserializer deserializer = odata.createDeserializer(requestFormat);
		DeserializerResult result = deserializer.entity(requestInputStream, edmEntityType);
		Entity requestEntity = result.getEntity();
		// 2.2 do the modification in backend
		List<UriParameter> keyPredicates = uriResourceEntitySet.getKeyPredicates();
		// Note that this updateEntity()-method is invoked for both PUT or PATCH operations
		HttpMethod httpMethod = request.getMethod();
		storage.updateEntityData(edmEntitySet, keyPredicates, requestEntity, httpMethod);
		
		//3. configure the response object
		response.setStatusCode(HttpStatusCode.NO_CONTENT.getStatusCode());
	}

	
	public void deleteEntity(ODataRequest request, ODataResponse response, UriInfo uriInfo)
          throws ODataApplicationException {
		
		// 1. Retrieve the entity set which belongs to the requested entity 
		List<UriResource> resourcePaths = uriInfo.getUriResourceParts();
		// Note: only in our example we can assume that the first segment is the EntitySet
		UriResourceEntitySet uriResourceEntitySet = (UriResourceEntitySet) resourcePaths.get(0); 
		EdmEntitySet edmEntitySet = uriResourceEntitySet.getEntitySet();

		// 2. delete the data in backend
		List<UriParameter> keyPredicates = uriResourceEntitySet.getKeyPredicates();
		storage.deleteEntityData(edmEntitySet, keyPredicates);
		
		//3. configure the response object
		response.setStatusCode(HttpStatusCode.NO_CONTENT.getStatusCode());
	}

  @Override
  public void readMediaEntity(ODataRequest request, ODataResponse response, UriInfo uriInfo, ContentType responseFormat)
      throws ODataApplicationException, ODataLibraryException {
    
    final UriResource firstResoucePart = uriInfo.getUriResourceParts().get(0);
    if(firstResoucePart instanceof UriResourceEntitySet) {
      final EdmEntitySet edmEntitySet = Util.getEdmEntitySet(uriInfo);
      final UriResourceEntitySet uriResourceEntitySet = (UriResourceEntitySet) firstResoucePart;
      
      final Entity entity = storage.readEntityData(edmEntitySet, uriResourceEntitySet.getKeyPredicates());
      if(entity == null) {
        throw new ODataApplicationException("Entity not found", HttpStatusCode.NOT_FOUND.getStatusCode(), 
            Locale.ENGLISH);
      }

      final byte[] mediaContent = storage.readMedia(entity);
      final InputStream responseContent = odata.createFixedFormatSerializer().binary(mediaContent);
      
      response.setStatusCode(HttpStatusCode.OK.getStatusCode());
      response.setContent(responseContent);
      response.setHeader(HttpHeader.CONTENT_TYPE, entity.getMediaContentType());
    } else {
      throw new ODataApplicationException("Not implemented", HttpStatusCode.BAD_REQUEST.getStatusCode(), 
          Locale.ENGLISH);
    }
  }

  @Override
  public void createMediaEntity(ODataRequest request, ODataResponse response, UriInfo uriInfo,
      ContentType requestFormat, ContentType responseFormat) throws ODataApplicationException, ODataLibraryException {
    
    final EdmEntitySet edmEntitySet = Util.getEdmEntitySet(uriInfo);
    final byte[] mediaContent = odata.createFixedFormatDeserializer().binary(request.getBody());
    
    final Entity entity = storage.createMediaEntity(edmEntitySet.getEntityType(), 
                                                    requestFormat.toContentTypeString(), 
                                                    mediaContent);
    
    final ContextURL contextUrl = ContextURL.with().entitySet(edmEntitySet).suffix(Suffix.ENTITY).build();
    final EntitySerializerOptions opts = EntitySerializerOptions.with().contextURL(contextUrl).build();
    final SerializerResult serializerResult = odata.createSerializer(responseFormat).entity(serviceMetadata,
        edmEntitySet.getEntityType(), entity, opts);
    
    final String location = request.getRawBaseUri() + '/'
        + odata.createUriHelper().buildCanonicalURL(edmEntitySet, entity);
    response.setContent(serializerResult.getContent());
    response.setStatusCode(HttpStatusCode.CREATED.getStatusCode());
    response.setHeader(HttpHeader.LOCATION, location);
    response.setHeader(HttpHeader.CONTENT_TYPE, responseFormat.toContentTypeString());
  }

  @Override
  public void updateMediaEntity(ODataRequest request, ODataResponse response, UriInfo uriInfo,
      ContentType requestFormat, ContentType responseFormat) throws ODataApplicationException, ODataLibraryException {
    
    final UriResource firstResoucePart = uriInfo.getUriResourceParts().get(0);
    if (firstResoucePart instanceof UriResourceEntitySet) {
      final EdmEntitySet edmEntitySet = Util.getEdmEntitySet(uriInfo);
      final UriResourceEntitySet uriResourceEntitySet = (UriResourceEntitySet) firstResoucePart;

      final Entity entity = storage.readEntityData(edmEntitySet, uriResourceEntitySet.getKeyPredicates());
      if (entity == null) {
        throw new ODataApplicationException("Entity not found", HttpStatusCode.NOT_FOUND.getStatusCode(),
            Locale.ENGLISH);
      }
      
      final byte[] mediaContent = odata.createFixedFormatDeserializer().binary(request.getBody());
      storage.updateMedia(entity, requestFormat.toContentTypeString(), mediaContent);
      
      response.setStatusCode(HttpStatusCode.NO_CONTENT.getStatusCode());
    } else {
      throw new ODataApplicationException("Not implemented", HttpStatusCode.NOT_IMPLEMENTED.getStatusCode(), 
          Locale.ENGLISH);
    }
  }    

  @Override
  public void deleteMediaEntity(ODataRequest request, ODataResponse response, UriInfo uriInfo)
      throws ODataApplicationException, ODataLibraryException {
    
    /*
     * In this tutorial, the content of the media entity is stored in a special property.
     * So no additional steps to delete the content of the media entity are necessary.
     *  
     * A real service may store the content on the file system. So we have to take care to
     * delete external files too. 
     * 
     * DELETE request to /Advertisements(ID) will be dispatched to the deleteEntity(...) method
     * DELETE request to /Advertisements(ID)/$value will be dispatched to the deleteMediaEntity(...) method
     * 
     * So it is a good idea handle deletes in a central place.
     */
    
    deleteEntity(request, response, uriInfo);
  }
}
