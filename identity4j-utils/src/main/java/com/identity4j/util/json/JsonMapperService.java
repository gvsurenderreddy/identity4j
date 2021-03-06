package com.identity4j.util.json;

/*
 * #%L
 * Identity4J Utils
 * %%
 * Copyright (C) 2013 - 2017 LogonBox
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 * 
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
 */

import java.io.IOException;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * This singleton class is responsible for handling serialization/deserialization of POJOs into JSON.
 * <br />
 * <ul>
 *  <li>Here it encapsulates trivial configuration.</li> 
 *  <li>It also handles custom POJO setting from HttpResponseData.</li>
 * </ul>
 * 
 * @author gaurav
 */
public class JsonMapperService{
    private final ObjectMapper objectMapper = new ObjectMapper();
    
	private JsonMapperService() {
    	//not to include null properties in JSON generated
        objectMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
//    	objectMapper.configure(SerializationConfig.Feature.WRITE_NULL_PROPERTIES, false);
    }
    
    /**
     * Singleton instance holder
     * 
     * @author gaurav
     *
     */
	private static class LazyHolder {
		private static final JsonMapperService INSTANCE = new JsonMapperService();
	}
 
	public static JsonMapperService getInstance() {
		return LazyHolder.INSTANCE;
	}
    
	/**
	 * Maps a json String to Java object.
	 * 
	 * @param clazz json object is mapped to this class
	 * @param json
	 * @return mapped java object
	 */
    public <T> T getObject(Class<T> clazz, String json) {
        try {
			return objectMapper.readValue(json, clazz);
		} catch (Exception e) {
			throw new JsonMapperException(e.getMessage(), e);
		} 
    }
    
    /**
	 * Maps a json String to Java object specified by TypeReference.
	 * 
	 * @param typeReference json object is mapped to this type reference
	 * @param json
	 * @return mapped java object
	 */
    public <T> T getObject(TypeReference<T> typeReference, String json) {
        try {
			return objectMapper.readValue(json, typeReference);
		} catch (Exception e) {
			throw new JsonMapperException(e.getMessage(), e);
		} 
    }
    
    /**
     * Converts a Java object into json.
     * 
     * @param object
     * @return converted json string
     * @throws IOException
     */
    public String getJson(Object object) throws IOException{
        return objectMapper.writeValueAsString(object);
    }
    
    /**
     * Fetches a property from a json string. It converts the json into a java Map instance.
     * Hence we can fetch only first level properties only.
     * 
     * @param json
     * @param property
     * @return property value
     */
    public Object getJsonProperty(String json,String property) {
    	try {
	    	return objectMapper.readValue(json, Map.class).get(property);
	    } catch (Exception e) {
			throw new JsonMapperException(e.getMessage(), e);
		}
    }
    
    /**
     * Converts Map like objects into instance of class specified.
     * 
     * @param fromValue object similar to java Map.
     * @param toType object is converted into an instance of this class
     * @return
     */
    public <T> T convert(Object fromValue,Class<T> toType){
    	return objectMapper.convertValue(fromValue, toType);
    }
    
}
