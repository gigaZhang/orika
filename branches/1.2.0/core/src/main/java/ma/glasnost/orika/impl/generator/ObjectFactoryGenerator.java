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
package ma.glasnost.orika.impl.generator;


import static ma.glasnost.orika.impl.Specifications.*;
import static ma.glasnost.orika.metadata.TypeFactory.valueOf;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javassist.CannotCompileException;
import ma.glasnost.orika.Converter;
import ma.glasnost.orika.MapperFactory;
import ma.glasnost.orika.MappingContext;
import ma.glasnost.orika.MappingException;
import ma.glasnost.orika.constructor.ConstructorResolverStrategy;
import ma.glasnost.orika.converter.ConverterFactory;
import ma.glasnost.orika.impl.GeneratedObjectFactory;
import ma.glasnost.orika.metadata.ClassMap;
import ma.glasnost.orika.metadata.FieldMap;
import ma.glasnost.orika.metadata.MapperKey;
import ma.glasnost.orika.metadata.Type;
import ma.glasnost.orika.metadata.TypeFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.thoughtworks.paranamer.AdaptiveParanamer;
import com.thoughtworks.paranamer.AnnotationParanamer;
import com.thoughtworks.paranamer.BytecodeReadingParanamer;
import com.thoughtworks.paranamer.CachingParanamer;
import com.thoughtworks.paranamer.Paranamer;

public class ObjectFactoryGenerator {
    
    private final static Logger LOG = LoggerFactory.getLogger(ObjectFactoryGenerator.class);
    
    private final ConstructorResolverStrategy constructorResolverStrategy;
    private final MapperFactory mapperFactory;
    private final Paranamer paranamer;
    private final CompilerStrategy compilerStrategy;
    private final String nameSuffix;
    private final UsedTypesContext usedTypes ;
    
    public ObjectFactoryGenerator(MapperFactory mapperFactory, ConstructorResolverStrategy constructorResolverStrategy,
    		CompilerStrategy compilerStrategy) {
        this.mapperFactory = mapperFactory;
        this.compilerStrategy = compilerStrategy;
        this.nameSuffix = Integer.toHexString(System.identityHashCode(compilerStrategy));
        this.paranamer = new CachingParanamer(new AdaptiveParanamer(new BytecodeReadingParanamer(), new AnnotationParanamer()));
        this.constructorResolverStrategy = constructorResolverStrategy;
        this.usedTypes = new UsedTypesContext();
    }
    
    public GeneratedObjectFactory build(Type<?> type) {
        
        final String className = type.getSimpleName() + "ObjectFactory" + nameSuffix;
        
        try {
            final GeneratedSourceCode factoryCode = 
        			new GeneratedSourceCode(className,GeneratedObjectFactory.class,compilerStrategy);
        	
            addCreateMethod(factoryCode, type);
            
            GeneratedObjectFactory objectFactory = (GeneratedObjectFactory) factoryCode.getInstance();
            objectFactory.setMapperFacade(mapperFactory.getMapperFacade());
            objectFactory.setUsedTypes(usedTypes.getUsedTypesArray());
            
            return objectFactory;
            
        } catch (final Exception e) {
            throw new MappingException("exception while creating object factory for " + type.getName(), e);
        } 
    }
    
    private void addCreateMethod(GeneratedSourceCode context, Type<?> clazz) throws CannotCompileException {
        final CodeSourceBuilder out = new CodeSourceBuilder(usedTypes, mapperFactory);
        out.append("public Object create(Object s, " + MappingContext.class.getCanonicalName() + " mappingContext) {");
        out.append("if(s == null) throw new %s(\"source object must be not null\");", IllegalArgumentException.class.getCanonicalName());
        
        Set<Type<? extends Object>> sourceClasses = mapperFactory.lookupMappedClasses(clazz);
        
        if (sourceClasses != null && !sourceClasses.isEmpty()) {
            for (Type<? extends Object> sourceType : sourceClasses) {
                addSourceClassConstructor(out, clazz, sourceType);
            }
        }
        out.append("throw new %s(s.getClass().getCanonicalName() + \" is an unsupported source class : \"+s.getClass().getCanonicalName());",
                IllegalArgumentException.class.getCanonicalName());
        out.append("\n}");
        
        context.addMethod(out.toString());
    }
    
    private void addSourceClassConstructor(CodeSourceBuilder out, Type<?> type, Type<?> sourceClass) {
        List<FieldMap> properties = new ArrayList<FieldMap>();
        ClassMap<Object, Object> classMap = mapperFactory.getClassMap(new MapperKey(type,sourceClass)); 
        if (classMap==null) {
        	classMap = mapperFactory.getClassMap(new MapperKey(sourceClass,type));
        }
        boolean aToB = classMap.getBType().equals(type);
        
        try {
            Constructor<?> constructor = (Constructor<?>) constructorResolverStrategy.resolve(classMap, type);
            
            if (constructor == null) {
                throw new IllegalArgumentException("no constructors found for " + type);
            }
            
            String[] parameters = paranamer.lookupParameterNames(constructor);
            Class<?>[] constructorArguments = constructor.getParameterTypes();
            
            // TODO need optimizations
            int argIndex = 0;
            for (String param : parameters) {
                for (FieldMap fieldMap : classMap.getFieldsMapping()) {
                    if (!aToB)
                        fieldMap = fieldMap.flip();
                    if (param.equals(fieldMap.getDestination().getName())) {
                    	// destination property should be compared against
                    	// the constructor argument 
                    	fieldMap = fieldMap.copy();
                    	fieldMap.getDestination().setType(TypeFactory.valueOf(constructorArguments[argIndex]));
                    	properties.add(fieldMap);
                        break;
                    }
                }
                ++argIndex;
            }
            
            if (parameters.length != properties.size()) {
                throw new MappingException("Can not find all constructor's parameters");
            }
            
            out.ifInstanceOf("s", sourceClass).then();
            out.append("%s source = (%s) s;", sourceClass.getCanonicalName(), sourceClass.getCanonicalName());
            argIndex = 0;
            for (FieldMap fieldMap : properties) {
            	
                Class<?> targetClass = constructorArguments[argIndex];
                VariableRef v = new VariableRef(valueOf(targetClass), "arg" + argIndex++);
                VariableRef s = new VariableRef(fieldMap.getSource(), "source");
               
                out.statement(v.declare());
                
                if (generateConverterCode(out, v, fieldMap)) {
                    continue;
                }
                try {
                    
                    if (fieldMap.is(aWrapperToPrimitive())) {
                        out.ifNotNull(s).setPrimitive(v, s);
                    } else if (fieldMap.is(aPrimitiveToWrapper())) {
                        out.fromPrimitiveToWrapper(v, s);
                    } else if (fieldMap.is(aPrimitive())) {
                        out.copyByReference(v, s);
                    } else if (fieldMap.is(immutable())) {
                        out.ifNotNull(s).copyByReference(v, s);
                    } else if (fieldMap.is(anArray())) {
                        out.fromArrayOrCollectionToArray(v, s);
                    } else if (fieldMap.is(aCollection())) {
                        out.fromArrayOrCollectionToCollection(v, s, fieldMap.getDestination(), fieldMap.getDestination().getType());
                    } else if (fieldMap.is(aStringToPrimitiveOrWrapper())) { 
                        out.fromStringToStringConvertable(v, s);
                    } else if (fieldMap.is(aConversionToString())) {
                        out.fromAnyTypeToString(v, s);
                    } else { /**/
                        out.fromObjectToObject(v, s, null);
                    }
                    
                } catch (final Exception e) {
                }
            }
            
            out.append("return new %s", type.getCanonicalName()).append("(");
            for (int i = 0; i < properties.size(); i++) {
                out.append("arg%d", i);
                if (i < properties.size() - 1) {
                    out.append(",");
                }
            }
            out.append(");").end();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            LOG.warn("Could not find " + type.getName() + " constructor's parameters name");
            /* SKIP */
        }
    }
    
    private boolean generateConverterCode(final CodeSourceBuilder code, VariableRef v, FieldMap fieldMap) {
        
        VariableRef s = new VariableRef(fieldMap.getSource(), "source");
        final Type<?> destinationType = fieldMap.getDestination().getType();
        
        Converter<Object, Object> converter = null;
        ConverterFactory converterFactory = mapperFactory.getConverterFactory();
        if (fieldMap.getConverterId() != null) {
            converter = converterFactory.getConverter(fieldMap.getConverterId());
        } else {
            converter = converterFactory.getConverter(s.type(), destinationType);
        }
        
        if (converter != null) {
            code.ifNotNull(s).then().convert(v, s, fieldMap.getConverterId()).end();
            return true;
        } else {
            return false;
        }
    }
}