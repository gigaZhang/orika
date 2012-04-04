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

import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import ma.glasnost.orika.impl.generator.eclipsejdt.CompilationUnit;
import ma.glasnost.orika.impl.generator.eclipsejdt.CompilerRequestor;
import ma.glasnost.orika.impl.generator.eclipsejdt.NameEnvironment;

import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.ToolFactory;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.formatter.CodeFormatter;
import org.eclipse.jdt.core.formatter.DefaultCodeFormatterConstants;
import org.eclipse.jdt.internal.compiler.Compiler;
import org.eclipse.jdt.internal.compiler.DefaultErrorHandlingPolicies;
import org.eclipse.jdt.internal.compiler.env.ICompilationUnit;
import org.eclipse.jdt.internal.compiler.env.INameEnvironment;
import org.eclipse.jdt.internal.compiler.impl.CompilerOptions;
import org.eclipse.jdt.internal.compiler.problem.DefaultProblemFactory;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.eclipse.text.edits.TextEdit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Uses Eclipse JDT to format and compile the source for the specified
 * GeneratedSourceCode objects.<br><br>
 * 
 * By default, this compiler strategy writes formatted source files relative to the current
 * class path root.
 * 
 * @author matt.deboer@gmail.com
 */
public class EclipseJdtCompiler {

    private final static Logger LOG = LoggerFactory.getLogger(EclipseJdtCompiler.class);

    private static final String JAVA_COMPILER_SOURCE_VERSION = "1.5";
    private static final String JAVA_COMPILER_COMPLIANCE_VERSION = "1.5";
    private static final String JAVA_COMPILER_CODEGEN_TARGET_PLATFORM_VERSION = "1.5";
    private static final String JAVA_SOURCE_ENCODING = "UTF-8";
    
    private final ByteCodeClassLoader byteCodeClassLoader;
    private final CodeFormatter formatter;
    private final INameEnvironment compilerNameEnvironment;
    private final CompilerRequestor compilerRequester;
    private final Compiler compiler;
    
   
	public EclipseJdtCompiler() {
		
		this.byteCodeClassLoader = new ByteCodeClassLoader(getClass()
		        .getClassLoader());
		this.formatter = ToolFactory
		        .createCodeFormatter(getFormattingOptions());
		this.compilerNameEnvironment = new NameEnvironment(
		        this.byteCodeClassLoader);
		this.compilerRequester = new CompilerRequestor();
		this.compiler = new Compiler(
		        compilerNameEnvironment,
		        DefaultErrorHandlingPolicies.proceedWithAllProblems(),
		        getCompilerOptions(),
		        compilerRequester,
		        new DefaultProblemFactory(Locale.getDefault()));
	}

    /**
     * Return the options to be passed when creating {@link CodeFormatter}
     * instance.
     * 
     * @return
     */
	private Map<Object, Object> getFormattingOptions() {

		@SuppressWarnings("unchecked")
		Map<Object, Object> options = DefaultCodeFormatterConstants
		        .getEclipseDefaultSettings();
		options.put(JavaCore.COMPILER_SOURCE, JAVA_COMPILER_SOURCE_VERSION);
		options.put(JavaCore.COMPILER_COMPLIANCE, JAVA_COMPILER_COMPLIANCE_VERSION);
		options.put(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM,
		        JAVA_COMPILER_CODEGEN_TARGET_PLATFORM_VERSION);
		return options;
	}

	private CompilerOptions getCompilerOptions() {

		Map<Object, Object> options = new HashMap<Object, Object>();

		options.put(CompilerOptions.OPTION_LocalVariableAttribute, CompilerOptions.GENERATE);
		options.put(CompilerOptions.OPTION_LineNumberAttribute, CompilerOptions.GENERATE);
		options.put(CompilerOptions.OPTION_SourceFileAttribute, CompilerOptions.GENERATE);

		options.put(CompilerOptions.OPTION_SuppressWarnings, CompilerOptions.ENABLED);

		options.put(CompilerOptions.OPTION_Source, JAVA_COMPILER_SOURCE_VERSION);
		options.put(CompilerOptions.OPTION_TargetPlatform, JAVA_COMPILER_CODEGEN_TARGET_PLATFORM_VERSION);
		options.put(CompilerOptions.OPTION_Encoding, JAVA_SOURCE_ENCODING);
		options.put(CompilerOptions.OPTION_ReportDeprecation, CompilerOptions.IGNORE);

		// Ignore unchecked types and raw types
		options.put(JavaCore.COMPILER_PB_UNCHECKED_TYPE_OPERATION, CompilerOptions.IGNORE);
		options.put(JavaCore.COMPILER_PB_RAW_TYPE_REFERENCE, CompilerOptions.IGNORE);

		return new CompilerOptions(options);
	}

    /**
     * Format the source code using the Eclipse text formatter
     */
	public String formatSource(String code) {

		String lineSeparator = "\n";

		TextEdit te = formatter.format(CodeFormatter.K_COMPILATION_UNIT, code,
		        0, code.length(), 0, lineSeparator);
		if (te == null) {
			throw new IllegalArgumentException(
			        "source code was unable to be formatted; \n"
			                + "//--- BEGIN ---\n" + code + "\n//--- END ---");
		}

		IDocument doc = new Document(code);
		try {
			te.apply(doc);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		String formattedCode = doc.get();

		return formattedCode;
	}


    public void assertTypeAccessible(Class<?> type)  throws IllegalStateException {
	
		if (!type.isPrimitive() && type.getClassLoader() != null) {

			String resourceName = type.getName().replace('.', '/') + ".class";
			if (type.isArray()) {
				// Strip off the "[L" prefix from the internal name
				resourceName = resourceName.substring(2);
			}
			InputStream is = byteCodeClassLoader.getResourceAsStream(resourceName);
			if (is == null) {
				throw new IllegalStateException(type + " is not accessible");
			}
		}
    }

    /**
     * Compile and return the (generated) class; this will also cause the
     * generated class to be detached from the class-pool, and any (optional)
     * source and/or class files to be written.
     * 
     * @return the (generated) compiled class
     * @throws ClassNotFoundException 
     * @throws CannotCompileException
     */
    public Class<?> compile(String source, String packageName, String className) throws ClassNotFoundException {
    	
    	Map<String, byte[]> compiledClasses = compile(source,
		        packageName, className, Thread.currentThread().getContextClassLoader());
    	
    	byte[] data = compiledClasses.get(className);
    	byteCodeClassLoader.putClassData(className, data);
    	
    	return byteCodeClassLoader.loadClass(packageName + "." + className);
    }
    
    private Map<String, byte[]> compile(String source, String packageName,
    		String className, ClassLoader classLoader) {

	
		CompilationUnit unit = new CompilationUnit(source, packageName,
		        className);

		Map<String, byte[]> compiledClasses = null;

		synchronized (compiler) {
			compilerRequester.reset();
			compiler.compile(new ICompilationUnit[] { unit });

			if (compilerRequester.getProblems() != null) {
				StringBuilder warningText = new StringBuilder();
				StringBuilder errorText = new StringBuilder();
				boolean hasErrors = false;
				for (IProblem p : compilerRequester.getProblems()) {
					if (p.isError()) {
						hasErrors = true;
						errorText.append("ERROR: " + p.toString() + "\n\n");
					} else {
						warningText.append("WARNING: " + p.toString() + "\n\n");
					}
				}
				if (hasErrors) {
					throw new RuntimeException(
					        "Compilation encountered errors:\n"
					                + errorText.toString() + "\n\n"
					                + warningText.toString());
				} else {
					LOG.warn("Compiler warnings:" + warningText.toString());
				}
			}
			compiledClasses = compilerRequester.getCompiledClassFiles();
		}
		return compiledClasses;
    }

}