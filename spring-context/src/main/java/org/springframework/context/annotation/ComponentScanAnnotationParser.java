/*
 * Copyright 2002-2018 the original author or authors.
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

package org.springframework.context.annotation;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanNameGenerator;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.filter.*;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import java.lang.annotation.Annotation;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Parser for the @{@link ComponentScan} annotation.
 *
 * @author Chris Beams
 * @author Juergen Hoeller
 * @author Sam Brannen
 * @since 3.1
 * @see ClassPathBeanDefinitionScanner#scan(String...)
 * @see ComponentScanBeanDefinitionParser
 */
class ComponentScanAnnotationParser {

	private final Environment environment;

	private final ResourceLoader resourceLoader;

	private final BeanNameGenerator beanNameGenerator;

	private final BeanDefinitionRegistry registry;


	public ComponentScanAnnotationParser(Environment environment, ResourceLoader resourceLoader,
			BeanNameGenerator beanNameGenerator, BeanDefinitionRegistry registry) {

		this.environment = environment;
		this.resourceLoader = resourceLoader;
		this.beanNameGenerator = beanNameGenerator;
		this.registry = registry;
	}


	public Set<BeanDefinitionHolder> parse(AnnotationAttributes componentScan, final String declaringClass) {
		ClassPathBeanDefinitionScanner scanner = new ClassPathBeanDefinitionScanner(this.registry,
				componentScan.getBoolean("useDefaultFilters"), this.environment, this.resourceLoader);

		Class<? extends BeanNameGenerator> generatorClass = componentScan.getClass("nameGenerator");
		boolean useInheritedGenerator = (BeanNameGenerator.class == generatorClass);
		scanner.setBeanNameGenerator(useInheritedGenerator ? this.beanNameGenerator :
				BeanUtils.instantiateClass(generatorClass));

		ScopedProxyMode scopedProxyMode = componentScan.getEnum("scopedProxy");
		if (scopedProxyMode != ScopedProxyMode.DEFAULT) {
			scanner.setScopedProxyMode(scopedProxyMode);
		}
		else {
			Class<? extends ScopeMetadataResolver> resolverClass = componentScan.getClass("scopeResolver");
			scanner.setScopeMetadataResolver(BeanUtils.instantiateClass(resolverClass));
		}

		scanner.setResourcePattern(componentScan.getString("resourcePattern"));

		for (AnnotationAttributes filter : componentScan.getAnnotationArray("includeFilters")) {
			for (TypeFilter typeFilter : typeFiltersFor(filter)) {
				scanner.addIncludeFilter(typeFilter);
			}
		}
		for (AnnotationAttributes filter : componentScan.getAnnotationArray("excludeFilters")) {
			for (TypeFilter typeFilter : typeFiltersFor(filter)) {
				scanner.addExcludeFilter(typeFilter);
			}
		}

		boolean lazyInit = componentScan.getBoolean("lazyInit");
		if (lazyInit) {
			scanner.getBeanDefinitionDefaults().setLazyInit(true);
		}

		Set<String> basePackages = new LinkedHashSet<>();
		String[] basePackagesArray = componentScan.getStringArray("basePackages");
		for (String pkg : basePackagesArray) {
			String[] tokenized = StringUtils.tokenizeToStringArray(this.environment.resolvePlaceholders(pkg),
					ConfigurableApplicationContext.CONFIG_LOCATION_DELIMITERS);
			Collections.addAll(basePackages, tokenized);
		}
		for (Class<?> clazz : componentScan.getClassArray("basePackageClasses")) {
			basePackages.add(ClassUtils.getPackageName(clazz));
		}
		if (basePackages.isEmpty()) {
			basePackages.add(ClassUtils.getPackageName(declaringClass));
		}

		scanner.addExcludeFilter(new AbstractTypeHierarchyTraversingFilter(false, false) {
			@Override
			protected boolean matchClassName(String className) {
				return declaringClass.equals(className);
			}
		});

		 //首次断点进来，上面的一系列骚操作，就是获取属性，然后赋值等，最后得出的各个对象的具体内容如下：
		// this = {ComponentScanAnnotationParser@3509}
		// environment = {StandardServletEnvironment@2860} "StandardServletEnvironment {activeProfiles=[], defaultProfiles=[default], propertySources=[ConfigurationPropertySourcesPropertySource {name='configurationProperties'}, StubPropertySource {name='servletConfigInitParams'}, StubPropertySource {name='servletContextInitParams'}, MapPropertySource {name='systemProperties'}, OriginAwareSystemEnvironmentPropertySource {name='systemEnvironment'}, RandomValuePropertySource {name='random'}]}"
		// resourceLoader = {AnnotationConfigServletWebServerApplicationContext@2856} "org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext@11bb571c, started on Sat Nov 16 13:35:36 SGT 2019"
		// beanNameGenerator = {AnnotationBeanNameGenerator@4425}
		// registry = {DefaultListableBeanFactory@3508} "org.springframework.beans.factory.support.DefaultListableBeanFactory@299321e2: defining beans [org.springframework.context.annotation.internalConfigurationAnnotationProcessor,org.springframework.context.annotation.internalAutowiredAnnotationProcessor,org.springframework.context.annotation.internalCommonAnnotationProcessor,org.springframework.context.event.internalEventListenerProcessor,org.springframework.context.event.internalEventListenerFactory,springmvctheoryApplication,org.springframework.boot.autoconfigure.internalCachingMetadataReaderFactory]; root of factory hierarchy"
		//componentScan = {AnnotationAttributes@3616}  size = 11
		// 0 = {LinkedHashMap$Entry@3624} "value" ->
		// 1 = {LinkedHashMap$Entry@3625} "includeFilters" ->
		// 2 = {LinkedHashMap$Entry@3626} "excludeFilters" ->
		// 3 = {LinkedHashMap$Entry@3627} "resourcePattern" -> "**/*.class"
		// 4 = {LinkedHashMap$Entry@3628} "lazyInit" -> "false"
		// 5 = {LinkedHashMap$Entry@3629} "basePackages" ->
		// 6 = {LinkedHashMap$Entry@3630} "useDefaultFilters" -> "true"
		// 7 = {LinkedHashMap$Entry@3631} "basePackageClasses" ->
		// 8 = {LinkedHashMap$Entry@3632} "nameGenerator" -> "interface org.springframework.beans.factory.support.BeanNameGenerator"
		// 9 = {LinkedHashMap$Entry@3633} "scopeResolver" -> "class org.springframework.context.annotation.AnnotationScopeMetadataResolver"
		// 10 = {LinkedHashMap$Entry@3634} "scopedProxy" -> "DEFAULT"
		//declaringClass = "com.lzkspace.springmvctheory.SpringmvctheoryApplication"
		//scanner = {ClassPathBeanDefinitionScanner@4504}
		// registry = {DefaultListableBeanFactory@3508} "org.springframework.beans.factory.support.DefaultListableBeanFactory@299321e2: defining beans [org.springframework.context.annotation.internalConfigurationAnnotationProcessor,org.springframework.context.annotation.internalAutowiredAnnotationProcessor,org.springframework.context.annotation.internalCommonAnnotationProcessor,org.springframework.context.event.internalEventListenerProcessor,org.springframework.context.event.internalEventListenerFactory,springmvctheoryApplication,org.springframework.boot.autoconfigure.internalCachingMetadataReaderFactory]; root of factory hierarchy"
		// beanDefinitionDefaults = {BeanDefinitionDefaults@4515}
		// autowireCandidatePatterns = null
		// beanNameGenerator = {AnnotationBeanNameGenerator@4425}
		// scopeMetadataResolver = {AnnotationScopeMetadataResolver@4830}
		// includeAnnotationConfig = true
		// logger = {LogAdapter$Slf4jLocationAwareLog@4511}
		// resourcePattern = "**/*.class"
		// includeFilters = {LinkedList@4513}  size = 2
		// excludeFilters = {LinkedList@4514}  size = 3
		// environment = {StandardServletEnvironment@2860} "StandardServletEnvironment {activeProfiles=[], defaultProfiles=[default], propertySources=[ConfigurationPropertySourcesPropertySource {name='configurationProperties'}, StubPropertySource {name='servletConfigInitParams'}, StubPropertySource {name='servletContextInitParams'}, MapPropertySource {name='systemProperties'}, OriginAwareSystemEnvironmentPropertySource {name='systemEnvironment'}, RandomValuePropertySource {name='random'}]}"
		// conditionEvaluator = null
		// resourcePatternResolver = {AnnotationConfigServletWebServerApplicationContext@2856} "org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext@11bb571c, started on Sat Nov 16 13:35:36 SGT 2019"
		// metadataReaderFactory = {CachingMetadataReaderFactory@4703}
		// componentsIndex = null
		//generatorClass = {Class@2771} "interface org.springframework.beans.factory.support.BeanNameGenerator"
		// cachedConstructor = null
		// newInstanceCallerCache = null
		// name = "org.springframework.beans.factory.support.BeanNameGenerator"
		// classLoader = {Launcher$AppClassLoader@4545}
		// reflectionData = null
		// classRedefinedCount = 0
		// genericInfo = null
		// enumConstants = null
		// enumConstantDirectory = null
		// annotationData = null
		// annotationType = null
		// classValueMap = null
		//useInheritedGenerator = true
		//scopedProxyMode = {ScopedProxyMode@4769} "DEFAULT"
		//lazyInit = false
		//basePackages = {LinkedHashSet@5100}  size = 1
		// 0 = "com.lzkspace.springmvctheory"
		//basePackagesArray = {String[0]@3664}
		return scanner.doScan(StringUtils.toStringArray(basePackages));
	}

	private List<TypeFilter> typeFiltersFor(AnnotationAttributes filterAttributes) {
		List<TypeFilter> typeFilters = new ArrayList<>();
		FilterType filterType = filterAttributes.getEnum("type");

		for (Class<?> filterClass : filterAttributes.getClassArray("classes")) {
			switch (filterType) {
				case ANNOTATION:
					Assert.isAssignable(Annotation.class, filterClass,
							"@ComponentScan ANNOTATION type filter requires an annotation type");
					@SuppressWarnings("unchecked")
					Class<Annotation> annotationType = (Class<Annotation>) filterClass;
					typeFilters.add(new AnnotationTypeFilter(annotationType));
					break;
				case ASSIGNABLE_TYPE:
					typeFilters.add(new AssignableTypeFilter(filterClass));
					break;
				case CUSTOM:
					Assert.isAssignable(TypeFilter.class, filterClass,
							"@ComponentScan CUSTOM type filter requires a TypeFilter implementation");
					TypeFilter filter = BeanUtils.instantiateClass(filterClass, TypeFilter.class);
					ParserStrategyUtils.invokeAwareMethods(
							filter, this.environment, this.resourceLoader, this.registry);
					typeFilters.add(filter);
					break;
				default:
					throw new IllegalArgumentException("Filter type not supported with Class value: " + filterType);
			}
		}

		for (String expression : filterAttributes.getStringArray("pattern")) {
			switch (filterType) {
				case ASPECTJ:
					typeFilters.add(new AspectJTypeFilter(expression, this.resourceLoader.getClassLoader()));
					break;
				case REGEX:
					typeFilters.add(new RegexPatternTypeFilter(Pattern.compile(expression)));
					break;
				default:
					throw new IllegalArgumentException("Filter type not supported with String pattern: " + filterType);
			}
		}

		return typeFilters;
	}

}
