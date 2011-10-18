/*
 * Orika - simpler, better and faster Java bean mapping
 * 
 * Copyright (C) 2011 Orika authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ma.glasnost.orika.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import ma.glasnost.orika.Converter;
import ma.glasnost.orika.Mapper;
import ma.glasnost.orika.MapperFacade;
import ma.glasnost.orika.MapperFactory;
import ma.glasnost.orika.MappingContext;
import ma.glasnost.orika.MappingException;
import ma.glasnost.orika.ObjectFactory;
import ma.glasnost.orika.metadata.ClassMap;
import ma.glasnost.orika.metadata.ClassMapBuilder;
import ma.glasnost.orika.metadata.ConverterKey;
import ma.glasnost.orika.metadata.MapperKey;
import ma.glasnost.orika.proxy.HibernateUnenhanceStrategy;
import ma.glasnost.orika.proxy.UnenhanceStrategy;

/**
 * The mapper factory is the heart of Orika, a small container where metadata
 * are stored, it's used by other components, to look up for generated mappers,
 * converters, object factories ... etc.
 * 
 * @author S.M. El Aatifi
 * 
 */
public class DefaultMapperFactory implements MapperFactory {
    
    private final MapperFacade mapperFacade;
    private final MapperGenerator mapperGenerator;
    private final Set<ClassMap<?, ?>> classMaps;
    private final Map<MapperKey, GeneratedMapperBase> mappersRegistry;
    private final Map<Object, Converter<?, ?>> convertersRegistry;
    private final Map<Class<?>, ObjectFactory<?>> objectFactoryRegistry;
    private final Map<Class<?>, Set<Class<?>>> aToBRegistry;
    
    private final Map<MapperKey, Set<ClassMap<Object, Object>>> usedMapperMetadataRegistry;
    
    private DefaultMapperFactory(Set<ClassMap<?, ?>> classMaps) {
        this.mapperFacade = new MapperFacadeImpl(this, getUnenhanceStrategy());
        this.mapperGenerator = new MapperGenerator(this);
        this.classMaps = Collections.synchronizedSet(new HashSet<ClassMap<?, ?>>());
        this.mappersRegistry = new ConcurrentHashMap<MapperKey, GeneratedMapperBase>();
        this.convertersRegistry = new ConcurrentHashMap<Object, Converter<?, ?>>();
        this.aToBRegistry = new ConcurrentHashMap<Class<?>, Set<Class<?>>>();
        this.usedMapperMetadataRegistry = new ConcurrentHashMap<MapperKey, Set<ClassMap<Object, Object>>>();
        objectFactoryRegistry = new ConcurrentHashMap<Class<?>, ObjectFactory<?>>();
        
        if (classMaps != null) {
            for (final ClassMap<?, ?> classMap : classMaps) {
                registerClassMap(classMap);
            }
        }
    }
    
    public DefaultMapperFactory() {
        this(null);
    }
    
    UnenhanceStrategy getUnenhanceStrategy() {
        try {
            Class.forName("org.hibernate.proxy.HibernateProxy");
            return new HibernateUnenhanceStrategy();
        } catch (final Throwable e) {
            // TODO add warning
            return new UnenhanceStrategy() {
                
                public <T> T unenhanceObject(T object) {
                    return object;
                }
                
                @SuppressWarnings("unchecked")
                public <T> Class<T> unenhanceClass(T object) {
                    return (Class<T>) object.getClass();
                }
            };
        }
    }
    
    public GeneratedMapperBase lookupMapper(MapperKey mapperKey) {
        if (!mappersRegistry.containsKey(mapperKey)) {
            final ClassMap<?, ?> classMap = ClassMapBuilder.map(mapperKey.getAType(), mapperKey.getBType()).byDefault().toClassMap();
            buildMapper(classMap);
        }
        return mappersRegistry.get(mapperKey);
    }
    
    public <S, D> void registerConverter(Converter<S, D> converter, Class<? extends S> sourceClass, Class<? extends D> destinationClass) {
        convertersRegistry.put(new ConverterKey(sourceClass, destinationClass), converter);
    }
    
    @SuppressWarnings("unchecked")
    public <S, D> Converter<S, D> lookupConverter(Class<S> source, Class<D> destination) {
        return (Converter<S, D>) convertersRegistry.get(new ConverterKey(source, destination));
    }
    
    public MapperFacade getMapperFacade() {
        return mapperFacade;
    }
    
    public <D> void registerObjectFactory(ObjectFactory<D> objectFactory, Class<D> destinationClass) {
        objectFactoryRegistry.put(destinationClass, objectFactory);
    }
    
    @SuppressWarnings("unchecked")
    public <T> ObjectFactory<T> lookupObjectFactory(Class<T> targetClass) {
        if (targetClass == null) {
            return null;
        }
        return (ObjectFactory<T>) objectFactoryRegistry.get(targetClass);
    }
    
    @SuppressWarnings("unchecked")
    public <S, D> Class<? extends D> lookupConcreteDestinationClass(Class<S> sourceClass, Class<D> destinationClass, MappingContext context) {
        final Class<? extends D> concreteClass = context.getConcreteClass(sourceClass, destinationClass);
        
        if (concreteClass != null) {
            return concreteClass;
        }
        
        final Set<Class<?>> destinationSet = aToBRegistry.get(sourceClass);
        if (destinationSet == null || destinationSet.isEmpty()) {
            return null;
        }
        
        for (final Class<?> clazz : destinationSet) {
            if (destinationClass.isAssignableFrom(clazz)) {
                return (Class<? extends D>) clazz;
                
            }
        }
        return concreteClass;
    }
    
    public <S, D> void registerClassMap(ClassMap<S, D> classMap) {
        classMaps.add(classMap);
    }
    
    public void build() {
        
        buildClassMapRegistry();
        
        for (final ClassMap<?, ?> classMap : classMaps) {
            buildMapper(classMap);
        }
        
        for (final ClassMap<?, ?> classMap : classMaps) {
            initializeUsedMappers(classMap);
        }
        
    }
    
    public Set<ClassMap<Object, Object>> lookupUsedClassMap(MapperKey mapperKey) {
        Set<ClassMap<Object, Object>> usedClassMapSet = usedMapperMetadataRegistry.get(mapperKey);
        if (usedClassMapSet == null) {
            usedClassMapSet = Collections.emptySet();
        }
        return usedClassMapSet;
    }
    
    private void buildClassMapRegistry() {
        // prepare a map for classmap (stored as set)
        Map<MapperKey, ClassMap<Object, Object>> classMapsDictionnary = new HashMap<MapperKey, ClassMap<Object, Object>>();
        
        @SuppressWarnings({ "rawtypes", "unchecked" })
        Set<ClassMap<Object, Object>> set = (Set) classMaps;
        
        for (final ClassMap<Object, Object> classMap : set) {
            classMapsDictionnary.put(new MapperKey(classMap.getAType(), classMap.getBType()), classMap);
        }
        
        for (final ClassMap<?, ?> classMap : classMaps) {
            MapperKey key = new MapperKey(classMap.getAType(), classMap.getBType());
            
            Set<ClassMap<Object, Object>> usedClassMapSet = new HashSet<ClassMap<Object, Object>>();
            
            for (final MapperKey parentMapperKey : classMap.getUsedMappers()) {
                ClassMap<Object, Object> usedClassMap = classMapsDictionnary.get(parentMapperKey);
                if (usedClassMap == null) {
                    throw new MappingException("Can not find class map of this used mappers for : " + classMap.getMapperClassName());
                }
                usedClassMapSet.add(usedClassMap);
            }
            usedMapperMetadataRegistry.put(key, usedClassMapSet);
        }
        
    }
    
    @SuppressWarnings("unchecked")
    private void initializeUsedMappers(ClassMap<?, ?> classMap) {
        Mapper<Object, Object> mapper = lookupMapper(new MapperKey(classMap.getAType(), classMap.getBType()));
        
        List<Mapper<Object, Object>> parentMappers = new ArrayList<Mapper<Object, Object>>();
        
        for (MapperKey parentMapperKey : classMap.getUsedMappers()) {
            collectUsedMappers(classMap, parentMappers, parentMapperKey);
        }
        
        mapper.setUsedMappers(parentMappers.toArray(new Mapper[parentMappers.size()]));
    }
    
    private void collectUsedMappers(ClassMap<?, ?> classMap, List<Mapper<Object, Object>> parentMappers, MapperKey parentMapperKey) {
        Mapper<Object, Object> parentMapper = lookupMapper(parentMapperKey);
        if (parentMapper == null) {
            throw new MappingException("Can not find used mappers for : " + classMap.getMapperClassName());
        }
        parentMappers.add(parentMapper);
        
        Set<ClassMap<Object, Object>> usedClassMapSet = usedMapperMetadataRegistry.get(parentMapperKey);
        for (ClassMap<Object, Object> cm : usedClassMapSet) {
            collectUsedMappers(cm, parentMappers, new MapperKey(cm.getAType(), cm.getBType()));
        }
    }
    
    private void buildMapper(ClassMap<?, ?> classMap) {
        register(classMap.getAType(), classMap.getBType());
        register(classMap.getBType(), classMap.getAType());
        
        final MapperKey mapperKey = new MapperKey(classMap.getAType(), classMap.getBType());
        final GeneratedMapperBase mapper = this.mapperGenerator.build(classMap);
        mapper.setMapperFacade(mapperFacade);
        if (classMap.getCustomizedMapper() != null) {
            @SuppressWarnings("unchecked")
            final Mapper<Object, Object> customizedMapper = (Mapper<Object, Object>) classMap.getCustomizedMapper();
            mapper.setCustomMapper(customizedMapper);
        }
        mappersRegistry.put(mapperKey, mapper);
    }
    
    private <S, D> void register(Class<S> sourceClass, Class<D> destinationClass) {
        Set<Class<?>> destinationSet = aToBRegistry.get(sourceClass);
        if (destinationSet == null) {
            destinationSet = new HashSet<Class<?>>();
            aToBRegistry.put(sourceClass, destinationSet);
        }
        destinationSet.add(destinationClass);
    }
}
