/*
 * Copyright 2002-2019 the original author or authors.
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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.aop.framework.autoproxy.AutoProxyUtils;
import org.springframework.beans.PropertyValues;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.*;
import org.springframework.beans.factory.parsing.FailFastProblemReporter;
import org.springframework.beans.factory.parsing.PassThroughSourceExtractor;
import org.springframework.beans.factory.parsing.ProblemReporter;
import org.springframework.beans.factory.parsing.SourceExtractor;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.BeanNameGenerator;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.ConfigurationClassEnhancer.EnhancedConfiguration;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.MethodMetadata;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;

import java.util.*;

/**
 * {@link BeanFactoryPostProcessor} used for bootstrapping processing of
 * {@link Configuration @Configuration} classes.
 *
 * <p>Registered by default when using {@code <context:annotation-config/>} or
 * {@code <context:component-scan/>}. Otherwise, may be declared manually as
 * with any other BeanFactoryPostProcessor.
 *
 * <p>This post processor is priority-ordered as it is important that any
 * {@link Bean} methods declared in {@code @Configuration} classes have
 * their corresponding bean definitions registered before any other
 * {@link BeanFactoryPostProcessor} executes.
 *
 * @author Chris Beams
 * @author Juergen Hoeller
 * @author Phillip Webb
 * @since 3.0
 */
public class ConfigurationClassPostProcessor implements BeanDefinitionRegistryPostProcessor,
		PriorityOrdered, ResourceLoaderAware, BeanClassLoaderAware, EnvironmentAware {

	private static final String IMPORT_REGISTRY_BEAN_NAME =
			ConfigurationClassPostProcessor.class.getName() + ".importRegistry";


	private final Log logger = LogFactory.getLog(getClass());

	private SourceExtractor sourceExtractor = new PassThroughSourceExtractor();

	private ProblemReporter problemReporter = new FailFastProblemReporter();

	@Nullable
	private Environment environment;

	private ResourceLoader resourceLoader = new DefaultResourceLoader();

	@Nullable
	private ClassLoader beanClassLoader = ClassUtils.getDefaultClassLoader();

	private MetadataReaderFactory metadataReaderFactory = new CachingMetadataReaderFactory();

	private boolean setMetadataReaderFactoryCalled = false;

	private final Set<Integer> registriesPostProcessed = new HashSet<>();

	private final Set<Integer> factoriesPostProcessed = new HashSet<>();

	@Nullable
	private ConfigurationClassBeanDefinitionReader reader;

	private boolean localBeanNameGeneratorSet = false;

	/* Using short class names as default bean names */
	private BeanNameGenerator componentScanBeanNameGenerator = new AnnotationBeanNameGenerator();

	/* Using fully qualified class names as default bean names */
	private BeanNameGenerator importBeanNameGenerator = new AnnotationBeanNameGenerator() {
		@Override
		protected String buildDefaultBeanName(BeanDefinition definition) {
			String beanClassName = definition.getBeanClassName();
			Assert.state(beanClassName != null, "No bean class name set");
			return beanClassName;
		}
	};


	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE;  // within PriorityOrdered
	}

	/**
	 * Set the {@link SourceExtractor} to use for generated bean definitions
	 * that correspond to {@link Bean} factory methods.
	 */
	public void setSourceExtractor(@Nullable SourceExtractor sourceExtractor) {
		this.sourceExtractor = (sourceExtractor != null ? sourceExtractor : new PassThroughSourceExtractor());
	}

	/**
	 * Set the {@link ProblemReporter} to use.
	 * <p>Used to register any problems detected with {@link Configuration} or {@link Bean}
	 * declarations. For instance, an @Bean method marked as {@code final} is illegal
	 * and would be reported as a problem. Defaults to {@link FailFastProblemReporter}.
	 */
	public void setProblemReporter(@Nullable ProblemReporter problemReporter) {
		this.problemReporter = (problemReporter != null ? problemReporter : new FailFastProblemReporter());
	}

	/**
	 * Set the {@link MetadataReaderFactory} to use.
	 * <p>Default is a {@link CachingMetadataReaderFactory} for the specified
	 * {@linkplain #setBeanClassLoader bean class loader}.
	 */
	public void setMetadataReaderFactory(MetadataReaderFactory metadataReaderFactory) {
		Assert.notNull(metadataReaderFactory, "MetadataReaderFactory must not be null");
		this.metadataReaderFactory = metadataReaderFactory;
		this.setMetadataReaderFactoryCalled = true;
	}

	/**
	 * Set the {@link BeanNameGenerator} to be used when triggering component scanning
	 * from {@link Configuration} classes and when registering {@link Import}'ed
	 * configuration classes. The default is a standard {@link AnnotationBeanNameGenerator}
	 * for scanned components (compatible with the default in {@link ClassPathBeanDefinitionScanner})
	 * and a variant thereof for imported configuration classes (using unique fully-qualified
	 * class names instead of standard component overriding).
	 * <p>Note that this strategy does <em>not</em> apply to {@link Bean} methods.
	 * <p>This setter is typically only appropriate when configuring the post-processor as a
	 * standalone bean definition in XML, e.g. not using the dedicated {@code AnnotationConfig*}
	 * application contexts or the {@code <context:annotation-config>} element. Any bean name
	 * generator specified against the application context will take precedence over any set here.
	 * @since 3.1.1
	 * @see AnnotationConfigApplicationContext#setBeanNameGenerator(BeanNameGenerator)
	 * @see AnnotationConfigUtils#CONFIGURATION_BEAN_NAME_GENERATOR
	 */
	public void setBeanNameGenerator(BeanNameGenerator beanNameGenerator) {
		Assert.notNull(beanNameGenerator, "BeanNameGenerator must not be null");
		this.localBeanNameGeneratorSet = true;
		this.componentScanBeanNameGenerator = beanNameGenerator;
		this.importBeanNameGenerator = beanNameGenerator;
	}

	@Override
	public void setEnvironment(Environment environment) {
		Assert.notNull(environment, "Environment must not be null");
		this.environment = environment;
	}

	@Override
	public void setResourceLoader(ResourceLoader resourceLoader) {
		Assert.notNull(resourceLoader, "ResourceLoader must not be null");
		this.resourceLoader = resourceLoader;
		if (!this.setMetadataReaderFactoryCalled) {
			this.metadataReaderFactory = new CachingMetadataReaderFactory(resourceLoader);
		}
	}

	@Override
	public void setBeanClassLoader(ClassLoader beanClassLoader) {
		this.beanClassLoader = beanClassLoader;
		if (!this.setMetadataReaderFactoryCalled) {
			this.metadataReaderFactory = new CachingMetadataReaderFactory(beanClassLoader);
		}
	}


	/**
	 * Derive further bean definitions from the configuration classes in the registry.
	 */
	@Override
	public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
		int registryId = System.identityHashCode(registry);
		if (this.registriesPostProcessed.contains(registryId)) {
			throw new IllegalStateException(
					"postProcessBeanDefinitionRegistry already called on this post-processor against " + registry);
		}
		if (this.factoriesPostProcessed.contains(registryId)) {
			throw new IllegalStateException(
					"postProcessBeanFactory already called on this post-processor against " + registry);
		}
		this.registriesPostProcessed.add(registryId);
		// 上面操作没什么，重点在这里
		processConfigBeanDefinitions(registry);
	}

	/**
	 * Prepare the Configuration classes for servicing bean requests at runtime
	 * by replacing them with CGLIB-enhanced subclasses.
	 */
	@Override
	public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
		int factoryId = System.identityHashCode(beanFactory);
		if (this.factoriesPostProcessed.contains(factoryId)) {
			throw new IllegalStateException(
					"postProcessBeanFactory already called on this post-processor against " + beanFactory);
		}
		this.factoriesPostProcessed.add(factoryId);
		if (!this.registriesPostProcessed.contains(factoryId)) {
			// BeanDefinitionRegistryPostProcessor hook apparently not supported...
			// Simply call processConfigurationClasses lazily at this point then.
			processConfigBeanDefinitions((BeanDefinitionRegistry) beanFactory);
		}

		enhanceConfigurationClasses(beanFactory);
		beanFactory.addBeanPostProcessor(new ImportAwareBeanPostProcessor(beanFactory));
	}

	/**
	 * Build and validate a configuration model based on the registry of
	 * {@link Configuration} classes.
	 */
	public void processConfigBeanDefinitions(BeanDefinitionRegistry registry) {
		List<BeanDefinitionHolder> configCandidates = new ArrayList<>();
		String[] candidateNames = registry.getBeanDefinitionNames();

		// 首次断点进入这里，这里的candidateNames有多个，分别为
		// 0 = "org.springframework.context.annotation.internalConfigurationAnnotationProcessor"
		//1 = "org.springframework.context.annotation.internalAutowiredAnnotationProcessor"
		//2 = "org.springframework.context.annotation.internalCommonAnnotationProcessor"
		//3 = "org.springframework.context.event.internalEventListenerProcessor"
		//4 = "org.springframework.context.event.internalEventListenerFactory"
		//5 = "springmvctheoryApplication"
		//6 = "org.springframework.boot.autoconfigure.internalCachingMetadataReaderFactory"
		// 但是下面的遍历处理，configCandidates的值仅为
		// Bean definition with name 'springmvctheoryApplication': Generic bean: class [com.lzkspace.springmvctheory.SpringmvctheoryApplication]; scope=singleton; abstract=false; lazyInit=false; autowireMode=0; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=null; initMethodName=null; destroyMethodName=null
		for (String beanName : candidateNames) {
			BeanDefinition beanDef = registry.getBeanDefinition(beanName);
			if (beanDef.getAttribute(ConfigurationClassUtils.CONFIGURATION_CLASS_ATTRIBUTE) != null) {
				if (logger.isDebugEnabled()) {
					logger.debug("Bean definition has already been processed as a configuration class: " + beanDef);
				}
			}
			else if (ConfigurationClassUtils.checkConfigurationClassCandidate(beanDef, this.metadataReaderFactory)) {
				configCandidates.add(new BeanDefinitionHolder(beanDef, beanName));
			}
		}

		// Return immediately if no @Configuration classes were found
		if (configCandidates.isEmpty()) {
			return;
		}

		// Sort by previously determined @Order value, if applicable
		configCandidates.sort((bd1, bd2) -> {
			int i1 = ConfigurationClassUtils.getOrder(bd1.getBeanDefinition());
			int i2 = ConfigurationClassUtils.getOrder(bd2.getBeanDefinition());
			return Integer.compare(i1, i2);
		});

		// Detect any custom bean name generation strategy supplied through the enclosing application context
		SingletonBeanRegistry sbr = null;
		if (registry instanceof SingletonBeanRegistry) {
			sbr = (SingletonBeanRegistry) registry;
			if (!this.localBeanNameGeneratorSet) {
				BeanNameGenerator generator = (BeanNameGenerator) sbr.getSingleton(
						AnnotationConfigUtils.CONFIGURATION_BEAN_NAME_GENERATOR);
				if (generator != null) {
					this.componentScanBeanNameGenerator = generator;
					this.importBeanNameGenerator = generator;
				}
			}
		}

		if (this.environment == null) {
			this.environment = new StandardEnvironment();
		}

		// Parse each @Configuration class
		ConfigurationClassParser parser = new ConfigurationClassParser(
				this.metadataReaderFactory, this.problemReporter, this.environment,
				this.resourceLoader, this.componentScanBeanNameGenerator, registry);

		Set<BeanDefinitionHolder> candidates = new LinkedHashSet<>(configCandidates);
		Set<ConfigurationClass> alreadyParsed = new HashSet<>(configCandidates.size());
		do {
			// 首次断点进入这里，上面的操作只是做了一些处理，可略过，主要是下面的parse方法，这个方法就是将很多map类型对象加入到configClasses中，总共是44个
			parser.parse(candidates);
			// 首次断点进入这里，就是一个校验的操作
			parser.validate();

			Set<ConfigurationClass> configClasses = new LinkedHashSet<>(parser.getConfigurationClasses());
			configClasses.removeAll(alreadyParsed);

			// 首次断点进入这里，构造了一个reader
			// Read the model and create bean definitions based on its content
			if (this.reader == null) {
				this.reader = new ConfigurationClassBeanDefinitionReader(
						registry, this.sourceExtractor, this.resourceLoader, this.environment,
						this.importBeanNameGenerator, parser.getImportRegistry());
			}
			// 这里又是一个大逻辑。从上面可知，configClasses总共44个，遍历并调用里面的loadBeanDefinitionsForConfigurationClass方法，而这个方法
			// 的逻辑主要是判断configClasses的类型，判断是由@bean注解的方法，还是被import进去的类等等，不同的类型，最终的操作都是为了加到
			// reader里面的实例属性registry的beanDefinitionMap字段中，以springmvctheroyApplication为例，最终beanDefinitionMap的内容为：
			//0 = {ConcurrentHashMap$MapEntry@7865} "defaultServletHandlerMapping" -> "Root bean: class [null]; scope=; abstract=false; lazyInit=false; autowireMode=3; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration$EnableWebMvcConfiguration; factoryMethodName=defaultServletHandlerMapping; initMethodName=null; destroyMethodName=(inferred); defined in class path resource [org/springframework/boot/autoconfigure/web/servlet/WebMvcAutoConfiguration$EnableWebMvcConfiguration.class]"
			//1 = {ConcurrentHashMap$MapEntry@7866} "org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration$WebMvcAutoConfigurationAdapter$FaviconConfiguration" -> "Generic bean: class [org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration$WebMvcAutoConfigurationAdapter$FaviconConfiguration]; scope=singleton; abstract=false; lazyInit=false; autowireMode=0; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=null; initMethodName=null; destroyMethodName=null"
			//2 = {ConcurrentHashMap$MapEntry@7867} "applicationTaskExecutor" -> "Root bean: class [null]; scope=; abstract=false; lazyInit=true; autowireMode=3; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration; factoryMethodName=applicationTaskExecutor; initMethodName=null; destroyMethodName=(inferred); defined in class path resource [org/springframework/boot/autoconfigure/task/TaskExecutionAutoConfiguration.class]"
			//3 = {ConcurrentHashMap$MapEntry@7868} "characterEncodingFilter" -> "Root bean: class [null]; scope=; abstract=false; lazyInit=false; autowireMode=3; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=org.springframework.boot.autoconfigure.web.servlet.HttpEncodingAutoConfiguration; factoryMethodName=characterEncodingFilter; initMethodName=null; destroyMethodName=(inferred); defined in class path resource [org/springframework/boot/autoconfigure/web/servlet/HttpEncodingAutoConfiguration.class]"
			//4 = {ConcurrentHashMap$MapEntry@7869} "org.springframework.boot.autoconfigure.web.servlet.MultipartAutoConfiguration" -> "Generic bean: class [org.springframework.boot.autoconfigure.web.servlet.MultipartAutoConfiguration]; scope=singleton; abstract=false; lazyInit=false; autowireMode=0; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=null; initMethodName=null; destroyMethodName=null"
			//5 = {ConcurrentHashMap$MapEntry@7870} "org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration$DispatcherServletRegistrationConfiguration" -> "Generic bean: class [org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration$DispatcherServletRegistrationConfiguration]; scope=singleton; abstract=false; lazyInit=false; autowireMode=0; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=null; initMethodName=null; destroyMethodName=null"
			//6 = {ConcurrentHashMap$MapEntry@7871} "preserveErrorControllerTargetClassPostProcessor" -> "Root bean: class [org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration]; scope=; abstract=false; lazyInit=false; autowireMode=3; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=preserveErrorControllerTargetClassPostProcessor; initMethodName=null; destroyMethodName=(inferred); defined in class path resource [org/springframework/boot/autoconfigure/web/servlet/error/ErrorMvcAutoConfiguration.class]"
			//7 = {ConcurrentHashMap$MapEntry@7872} "org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration$JacksonObjectMapperBuilderConfiguration" -> "Generic bean: class [org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration$JacksonObjectMapperBuilderConfiguration]; scope=singleton; abstract=false; lazyInit=false; autowireMode=0; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=null; initMethodName=null; destroyMethodName=null"
			//8 = {ConcurrentHashMap$MapEntry@7873} "org.springframework.context.annotation.internalConfigurationAnnotationProcessor" -> "Root bean: class [org.springframework.context.annotation.ConfigurationClassPostProcessor]; scope=; abstract=false; lazyInit=false; autowireMode=0; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=null; initMethodName=null; destroyMethodName=null"
			//9 = {ConcurrentHashMap$MapEntry@7874} "propertySourcesPlaceholderConfigurer" -> "Root bean: class [org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration]; scope=; abstract=false; lazyInit=false; autowireMode=3; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=propertySourcesPlaceholderConfigurer; initMethodName=null; destroyMethodName=(inferred); defined in class path resource [org/springframework/boot/autoconfigure/context/PropertyPlaceholderAutoConfiguration.class]"
			//10 = {ConcurrentHashMap$MapEntry@7875} "faviconRequestHandler" -> "Root bean: class [null]; scope=; abstract=false; lazyInit=false; autowireMode=3; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration$WebMvcAutoConfigurationAdapter$FaviconConfiguration; factoryMethodName=faviconRequestHandler; initMethodName=null; destroyMethodName=(inferred); defined in class path resource [org/springframework/boot/autoconfigure/web/servlet/WebMvcAutoConfiguration$WebMvcAutoConfigurationAdapter$FaviconConfiguration.class]"
			//11 = {ConcurrentHashMap$MapEntry@7876} "beanNameViewResolver" -> "Root bean: class [null]; scope=; abstract=false; lazyInit=false; autowireMode=3; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration$WhitelabelErrorViewConfiguration; factoryMethodName=beanNameViewResolver; initMethodName=null; destroyMethodName=(inferred); defined in class path resource [org/springframework/boot/autoconfigure/web/servlet/error/ErrorMvcAutoConfiguration$WhitelabelErrorViewConfiguration.class]"
			//12 = {ConcurrentHashMap$MapEntry@7877} "loggingCodecCustomizer" -> "Root bean: class [null]; scope=; abstract=false; lazyInit=false; autowireMode=3; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=org.springframework.boot.autoconfigure.http.codec.CodecsAutoConfiguration$LoggingCodecConfiguration; factoryMethodName=loggingCodecCustomizer; initMethodName=null; destroyMethodName=(inferred); defined in class path resource [org/springframework/boot/autoconfigure/http/codec/CodecsAutoConfiguration$LoggingCodecConfiguration.class]"
			//13 = {ConcurrentHashMap$MapEntry@7878} "viewResolver" -> "Root bean: class [null]; scope=; abstract=false; lazyInit=false; autowireMode=3; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration$WebMvcAutoConfigurationAdapter; factoryMethodName=viewResolver; initMethodName=null; destroyMethodName=(inferred); defined in class path resource [org/springframework/boot/autoconfigure/web/servlet/WebMvcAutoConfiguration$WebMvcAutoConfigurationAdapter.class]"
			//14 = {ConcurrentHashMap$MapEntry@7879} "methodValidationPostProcessor" -> "Root bean: class [org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration]; scope=; abstract=false; lazyInit=false; autowireMode=3; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=methodValidationPostProcessor; initMethodName=null; destroyMethodName=(inferred); defined in class path resource [org/springframework/boot/autoconfigure/validation/ValidationAutoConfiguration.class]"
			//15 = {ConcurrentHashMap$MapEntry@7880} "stringHttpMessageConverter" -> "Root bean: class [null]; scope=; abstract=false; lazyInit=false; autowireMode=3; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration$StringHttpMessageConverterConfiguration; factoryMethodName=stringHttpMessageConverter; initMethodName=null; destroyMethodName=(inferred); defined in class path resource [org/springframework/boot/autoconfigure/http/HttpMessageConvertersAutoConfiguration$StringHttpMessageConverterConfiguration.class]"
			//16 = {ConcurrentHashMap$MapEntry@7881} "org.springframework.boot.autoconfigure.web.embedded.EmbeddedWebServerFactoryCustomizerAutoConfiguration$TomcatWebServerFactoryCustomizerConfiguration" -> "Generic bean: class [org.springframework.boot.autoconfigure.web.embedded.EmbeddedWebServerFactoryCustomizerAutoConfiguration$TomcatWebServerFactoryCustomizerConfiguration]; scope=singleton; abstract=false; lazyInit=false; autowireMode=0; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=null; initMethodName=null; destroyMethodName=null"
			//17 = {ConcurrentHashMap$MapEntry@7882} "tomcatServletWebServerFactoryCustomizer" -> "Root bean: class [null]; scope=; abstract=false; lazyInit=false; autowireMode=3; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration; factoryMethodName=tomcatServletWebServerFactoryCustomizer; initMethodName=null; destroyMethodName=(inferred); defined in class path resource [org/springframework/boot/autoconfigure/web/servlet/ServletWebServerFactoryAutoConfiguration.class]"
			//18 = {ConcurrentHashMap$MapEntry@7883} "org.springframework.boot.autoconfigure.admin.SpringApplicationAdminJmxAutoConfiguration" -> "Generic bean: class [org.springframework.boot.autoconfigure.admin.SpringApplicationAdminJmxAutoConfiguration]; scope=singleton; abstract=false; lazyInit=false; autowireMode=0; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=null; initMethodName=null; destroyMethodName=null"
			//19 = {ConcurrentHashMap$MapEntry@7884} "server-org.springframework.boot.autoconfigure.web.ServerProperties" -> "Generic bean: class [org.springframework.boot.autoconfigure.web.ServerProperties]; scope=; abstract=false; lazyInit=false; autowireMode=0; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=null; initMethodName=null; destroyMethodName=null"
			//20 = {ConcurrentHashMap$MapEntry@7885} "messageConverters" -> "Root bean: class [null]; scope=; abstract=false; lazyInit=false; autowireMode=3; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration; factoryMethodName=messageConverters; initMethodName=null; destroyMethodName=(inferred); defined in class path resource [org/springframework/boot/autoconfigure/http/HttpMessageConvertersAutoConfiguration.class]"
			//21 = {ConcurrentHashMap$MapEntry@7886} "jsonComponentModule" -> "Root bean: class [null]; scope=; abstract=false; lazyInit=false; autowireMode=3; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration; factoryMethodName=jsonComponentModule; initMethodName=null; destroyMethodName=(inferred); defined in class path resource [org/springframework/boot/autoconfigure/jackson/JacksonAutoConfiguration.class]"
			//22 = {ConcurrentHashMap$MapEntry@7887} "websocketServletWebServerCustomizer" -> "Root bean: class [null]; scope=; abstract=false; lazyInit=false; autowireMode=3; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=org.springframework.boot.autoconfigure.websocket.servlet.WebSocketServletAutoConfiguration$TomcatWebSocketConfiguration; factoryMethodName=websocketServletWebServerCustomizer; initMethodName=null; destroyMethodName=(inferred); defined in class path resource [org/springframework/boot/autoconfigure/websocket/servlet/WebSocketServletAutoConfiguration$TomcatWebSocketConfiguration.class]"
			//23 = {ConcurrentHashMap$MapEntry@7888} "org.springframework.context.event.internalEventListenerFactory" -> "Root bean: class [org.springframework.context.event.DefaultEventListenerFactory]; scope=; abstract=false; lazyInit=false; autowireMode=0; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=null; initMethodName=null; destroyMethodName=null"
			//24 = {ConcurrentHashMap$MapEntry@7889} "org.springframework.boot.autoconfigure.web.embedded.EmbeddedWebServerFactoryCustomizerAutoConfiguration" -> "Generic bean: class [org.springframework.boot.autoconfigure.web.embedded.EmbeddedWebServerFactoryCustomizerAutoConfiguration]; scope=singleton; abstract=false; lazyInit=false; autowireMode=0; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=null; initMethodName=null; destroyMethodName=null"
			//25 = {ConcurrentHashMap$MapEntry@7890} "mappingJackson2HttpMessageConverter" -> "Root bean: class [null]; scope=; abstract=false; lazyInit=false; autowireMode=3; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=org.springframework.boot.autoconfigure.http.JacksonHttpMessageConvertersConfiguration$MappingJackson2HttpMessageConverterConfiguration; factoryMethodName=mappingJackson2HttpMessageConverter; initMethodName=null; destroyMethodName=(inferred); defined in class path resource [org/springframework/boot/autoconfigure/http/JacksonHttpMessageConvertersConfiguration$MappingJackson2HttpMessageConverterConfiguration.class]"
			//26 = {ConcurrentHashMap$MapEntry@7891} "mbeanExporter" -> "Root bean: class [null]; scope=; abstract=false; lazyInit=false; autowireMode=3; dependencyCheck=0; autowireCandidate=true; primary=true; factoryBeanName=org.springframework.boot.autoconfigure.jmx.JmxAutoConfiguration; factoryMethodName=mbeanExporter; initMethodName=null; destroyMethodName=(inferred); defined in class path resource [org/springframework/boot/autoconfigure/jmx/JmxAutoConfiguration.class]"
			//27 = {ConcurrentHashMap$MapEntry@7892} "mbeanServer" -> "Root bean: class [null]; scope=; abstract=false; lazyInit=false; autowireMode=3; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=org.springframework.boot.autoconfigure.jmx.JmxAutoConfiguration; factoryMethodName=mbeanServer; initMethodName=null; destroyMethodName=(inferred); defined in class path resource [org/springframework/boot/autoconfigure/jmx/JmxAutoConfiguration.class]"
			//28 = {ConcurrentHashMap$MapEntry@7893} "servletWebServerFactoryCustomizer" -> "Root bean: class [null]; scope=; abstract=false; lazyInit=false; autowireMode=3; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration; factoryMethodName=servletWebServerFactoryCustomizer; initMethodName=null; destroyMethodName=(inferred); defined in class path resource [org/springframework/boot/autoconfigure/web/servlet/ServletWebServerFactoryAutoConfiguration.class]"
			//29 = {ConcurrentHashMap$MapEntry@7894} "mvcUrlPathHelper" -> "Root bean: class [null]; scope=; abstract=false; lazyInit=false; autowireMode=3; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration$EnableWebMvcConfiguration; factoryMethodName=mvcUrlPathHelper; initMethodName=null; destroyMethodName=(inferred); defined in class path resource [org/springframework/boot/autoconfigure/web/servlet/WebMvcAutoConfiguration$EnableWebMvcConfiguration.class]"
			//30 = {ConcurrentHashMap$MapEntry@7895} "webServerFactoryCustomizerBeanPostProcessor" -> "Root bean: class [org.springframework.boot.web.server.WebServerFactoryCustomizerBeanPostProcessor]; scope=; abstract=false; lazyInit=false; autowireMode=0; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=null; initMethodName=null; destroyMethodName=null"
			//31 = {ConcurrentHashMap$MapEntry@7896} "org.springframework.boot.autoconfigure.websocket.servlet.WebSocketServletAutoConfiguration" -> "Generic bean: class [org.springframework.boot.autoconfigure.websocket.servlet.WebSocketServletAutoConfiguration]; scope=singleton; abstract=false; lazyInit=false; autowireMode=0; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=null; initMethodName=null; destroyMethodName=null"
			//32 = {ConcurrentHashMap$MapEntry@7897} "org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration$Jackson2ObjectMapperBuilderCustomizerConfiguration" -> "Generic bean: class [org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration$Jackson2ObjectMapperBuilderCustomizerConfiguration]; scope=singleton; abstract=false; lazyInit=false; autowireMode=0; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=null; initMethodName=null; destroyMethodName=null"
			//33 = {ConcurrentHashMap$MapEntry@7898} "org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration$StringHttpMessageConverterConfiguration" -> "Generic bean: class [org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration$StringHttpMessageConverterConfiguration]; scope=singleton; abstract=false; lazyInit=false; autowireMode=0; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=null; initMethodName=null; destroyMethodName=null"
			//34 = {ConcurrentHashMap$MapEntry@7899} "standardJacksonObjectMapperBuilderCustomizer" -> "Root bean: class [null]; scope=; abstract=false; lazyInit=false; autowireMode=3; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration$Jackson2ObjectMapperBuilderCustomizerConfiguration; factoryMethodName=standardJacksonObjectMapperBuilderCustomizer; initMethodName=null; destroyMethodName=(inferred); defined in class path resource [org/springframework/boot/autoconfigure/jackson/JacksonAutoConfiguration$Jackson2ObjectMapperBuilderCustomizerConfiguration.class]"
			//35 = {ConcurrentHashMap$MapEntry@7900} "taskSchedulerBuilder" -> "Root bean: class [null]; scope=; abstract=false; lazyInit=false; autowireMode=3; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration; factoryMethodName=taskSchedulerBuilder; initMethodName=null; destroyMethodName=(inferred); defined in class path resource [org/springframework/boot/autoconfigure/task/TaskSchedulingAutoConfiguration.class]"
			//36 = {ConcurrentHashMap$MapEntry@7901} "servletComponentRegisteringPostProcessor" -> "Generic bean: class [org.springframework.boot.web.servlet.ServletComponentRegisteringPostProcessor]; scope=; abstract=false; lazyInit=false; autowireMode=0; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=null; initMethodName=null; destroyMethodName=null"
			//37 = {ConcurrentHashMap$MapEntry@7902} "org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration" -> "Generic bean: class [org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration]; scope=singleton; abstract=false; lazyInit=false; autowireMode=0; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=null; initMethodName=null; destroyMethodName=null"
			//38 = {ConcurrentHashMap$MapEntry@7903} "org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration" -> "Generic bean: class [org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration]; scope=singleton; abstract=false; lazyInit=false; autowireMode=0; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=null; initMethodName=null; destroyMethodName=null"
			//39 = {ConcurrentHashMap$MapEntry@7904} "org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration" -> "Generic bean: class [org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration]; scope=singleton; abstract=false; lazyInit=false; autowireMode=0; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=null; initMethodName=null; destroyMethodName=null"
			//40 = {ConcurrentHashMap$MapEntry@7905} "jacksonCodecCustomizer" -> "Root bean: class [null]; scope=; abstract=false; lazyInit=false; autowireMode=3; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=org.springframework.boot.autoconfigure.http.codec.CodecsAutoConfiguration$JacksonCodecConfiguration; factoryMethodName=jacksonCodecCustomizer; initMethodName=null; destroyMethodName=(inferred); defined in class path resource [org/springframework/boot/autoconfigure/http/codec/CodecsAutoConfiguration$JacksonCodecConfiguration.class]"
			//41 = {ConcurrentHashMap$MapEntry@7906} "conventionErrorViewResolver" -> "Root bean: class [null]; scope=; abstract=false; lazyInit=false; autowireMode=3; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration$DefaultErrorViewResolverConfiguration; factoryMethodName=conventionErrorViewResolver; initMethodName=null; destroyMethodName=(inferred); defined in class path resource [org/springframework/boot/autoconfigure/web/servlet/error/ErrorMvcAutoConfiguration$DefaultErrorViewResolverConfiguration.class]"
			//42 = {ConcurrentHashMap$MapEntry@7907} "org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration$EnableWebMvcConfiguration" -> "Generic bean: class [org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration$EnableWebMvcConfiguration]; scope=singleton; abstract=false; lazyInit=false; autowireMode=0; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=null; initMethodName=null; destroyMethodName=null"
			//43 = {ConcurrentHashMap$MapEntry@7908} "org.springframework.context.event.internalEventListenerProcessor" -> "Root bean: class [org.springframework.context.event.EventListenerMethodProcessor]; scope=; abstract=false; lazyInit=false; autowireMode=0; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=null; initMethodName=null; destroyMethodName=null"
			//44 = {ConcurrentHashMap$MapEntry@7909} "spring.mvc-org.springframework.boot.autoconfigure.web.servlet.WebMvcProperties" -> "Generic bean: class [org.springframework.boot.autoconfigure.web.servlet.WebMvcProperties]; scope=; abstract=false; lazyInit=false; autowireMode=0; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=null; initMethodName=null; destroyMethodName=null"
			//45 = {ConcurrentHashMap$MapEntry@7910} "localeCharsetMappingsCustomizer" -> "Root bean: class [null]; scope=; abstract=false; lazyInit=false; autowireMode=3; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=org.springframework.boot.autoconfigure.web.servlet.HttpEncodingAutoConfiguration; factoryMethodName=localeCharsetMappingsCustomizer; initMethodName=null; destroyMethodName=(inferred); defined in class path resource [org/springframework/boot/autoconfigure/web/servlet/HttpEncodingAutoConfiguration.class]"
			//46 = {ConcurrentHashMap$MapEntry@7911} "formContentFilter" -> "Root bean: class [null]; scope=; abstract=false; lazyInit=false; autowireMode=3; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration; factoryMethodName=formContentFilter; initMethodName=null; destroyMethodName=(inferred); defined in class path resource [org/springframework/boot/autoconfigure/web/servlet/WebMvcAutoConfiguration.class]"
			//47 = {ConcurrentHashMap$MapEntry@7912} "multipartConfigElement" -> "Root bean: class [null]; scope=; abstract=false; lazyInit=false; autowireMode=3; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=org.springframework.boot.autoconfigure.web.servlet.MultipartAutoConfiguration; factoryMethodName=multipartConfigElement; initMethodName=null; destroyMethodName=(inferred); defined in class path resource [org/springframework/boot/autoconfigure/web/servlet/MultipartAutoConfiguration.class]"
			//48 = {ConcurrentHashMap$MapEntry@7913} "requestContextFilter" -> "Root bean: class [org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration$WebMvcAutoConfigurationAdapter]; scope=; abstract=false; lazyInit=false; autowireMode=3; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=requestContextFilter; initMethodName=null; destroyMethodName=(inferred); defined in class path resource [org/springframework/boot/autoconfigure/web/servlet/WebMvcAutoConfiguration$WebMvcAutoConfigurationAdapter.class]"
			//49 = {ConcurrentHashMap$MapEntry@7914} "defaultViewResolver" -> "Root bean: class [null]; scope=; abstract=false; lazyInit=false; autowireMode=3; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration$WebMvcAutoConfigurationAdapter; factoryMethodName=defaultViewResolver; initMethodName=null; destroyMethodName=(inferred); defined in class path resource [org/springframework/boot/autoconfigure/web/servlet/WebMvcAutoConfiguration$WebMvcAutoConfigurationAdapter.class]"
			//50 = {ConcurrentHashMap$MapEntry@7915} "jacksonObjectMapperBuilder" -> "Root bean: class [null]; scope=; abstract=false; lazyInit=false; autowireMode=3; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration$JacksonObjectMapperBuilderConfiguration; factoryMethodName=jacksonObjectMapperBuilder; initMethodName=null; destroyMethodName=(inferred); defined in class path resource [org/springframework/boot/autoconfigure/jackson/JacksonAutoConfiguration$JacksonObjectMapperBuilderConfiguration.class]"
			//51 = {ConcurrentHashMap$MapEntry@7916} "spring.task.scheduling-org.springframework.boot.autoconfigure.task.TaskSchedulingProperties" -> "Generic bean: class [org.springframework.boot.autoconfigure.task.TaskSchedulingProperties]; scope=; abstract=false; lazyInit=false; autowireMode=0; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=null; initMethodName=null; destroyMethodName=null"
			//52 = {ConcurrentHashMap$MapEntry@7917} "org.springframework.boot.autoconfigure.http.codec.CodecsAutoConfiguration$LoggingCodecConfiguration" -> "Generic bean: class [org.springframework.boot.autoconfigure.http.codec.CodecsAutoConfiguration$LoggingCodecConfiguration]; scope=singleton; abstract=false; lazyInit=false; autowireMode=0; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=null; initMethodName=null; destroyMethodName=null"
			//53 = {ConcurrentHashMap$MapEntry@7918} "restTemplateBuilder" -> "Root bean: class [null]; scope=; abstract=false; lazyInit=false; autowireMode=3; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=org.springframework.boot.autoconfigure.web.client.RestTemplateAutoConfiguration; factoryMethodName=restTemplateBuilder; initMethodName=null; destroyMethodName=(inferred); defined in class path resource [org/springframework/boot/autoconfigure/web/client/RestTemplateAutoConfiguration.class]"
			//54 = {ConcurrentHashMap$MapEntry@7919} "multipartResolver" -> "Root bean: class [null]; scope=; abstract=false; lazyInit=false; autowireMode=3; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=org.springframework.boot.autoconfigure.web.servlet.MultipartAutoConfiguration; factoryMethodName=multipartResolver; initMethodName=null; destroyMethodName=(inferred); defined in class path resource [org/springframework/boot/autoconfigure/web/servlet/MultipartAutoConfiguration.class]"
			//55 = {ConcurrentHashMap$MapEntry@7920} "requestMappingHandlerMapping" -> "Root bean: class [null]; scope=; abstract=false; lazyInit=false; autowireMode=3; dependencyCheck=0; autowireCandidate=true; primary=true; factoryBeanName=org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration$EnableWebMvcConfiguration; factoryMethodName=requestMappingHandlerMapping; initMethodName=null; destroyMethodName=(inferred); defined in class path resource [org/springframework/boot/autoconfigure/web/servlet/WebMvcAutoConfiguration$EnableWebMvcConfiguration.class]"
			//56 = {ConcurrentHashMap$MapEntry@7921} "requestMappingHandlerAdapter" -> "Root bean: class [null]; scope=; abstract=false; lazyInit=false; autowireMode=3; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration$EnableWebMvcConfiguration; factoryMethodName=requestMappingHandlerAdapter; initMethodName=null; destroyMethodName=(inferred); defined in class path resource [org/springframework/boot/autoconfigure/web/servlet/WebMvcAutoConfiguration$EnableWebMvcConfiguration.class]"
			//57 = {ConcurrentHashMap$MapEntry@7922} "org.springframework.boot.autoconfigure.web.servlet.HttpEncodingAutoConfiguration" -> "Generic bean: class [org.springframework.boot.autoconfigure.web.servlet.HttpEncodingAutoConfiguration]; scope=singleton; abstract=false; lazyInit=false; autowireMode=0; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=null; initMethodName=null; destroyMethodName=null"
			//58 = {ConcurrentHashMap$MapEntry@7923} "org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration" -> "Generic bean: class [org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration]; scope=singleton; abstract=false; lazyInit=false; autowireMode=0; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=null; initMethodName=null; destroyMethodName=null"
			//59 = {ConcurrentHashMap$MapEntry@7924} "mvcHandlerMappingIntrospector" -> "Root bean: class [null]; scope=; abstract=false; lazyInit=true; autowireMode=3; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration$EnableWebMvcConfiguration; factoryMethodName=mvcHandlerMappingIntrospector; initMethodName=null; destroyMethodName=(inferred); defined in class path resource [org/springframework/boot/autoconfigure/web/servlet/WebMvcAutoConfiguration$EnableWebMvcConfiguration.class]"
			//60 = {ConcurrentHashMap$MapEntry@7925} "springApplicationAdminRegistrar" -> "Root bean: class [null]; scope=; abstract=false; lazyInit=false; autowireMode=3; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=org.springframework.boot.autoconfigure.admin.SpringApplicationAdminJmxAutoConfiguration; factoryMethodName=springApplicationAdminRegistrar; initMethodName=null; destroyMethodName=(inferred); defined in class path resource [org/springframework/boot/autoconfigure/admin/SpringApplicationAdminJmxAutoConfiguration.class]"
			//61 = {ConcurrentHashMap$MapEntry@7926} "org.springframework.boot.autoconfigure.condition.BeanTypeRegistry" -> "Generic bean: class [org.springframework.boot.autoconfigure.condition.BeanTypeRegistry]; scope=; abstract=false; lazyInit=false; autowireMode=0; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=null; initMethodName=null; destroyMethodName=null"
			//62 = {ConcurrentHashMap$MapEntry@7927} "spring.info-org.springframework.boot.autoconfigure.info.ProjectInfoProperties" -> "Generic bean: class [org.springframework.boot.autoconfigure.info.ProjectInfoProperties]; scope=; abstract=false; lazyInit=false; autowireMode=0; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=null; initMethodName=null; destroyMethodName=null"
			//63 = {ConcurrentHashMap$MapEntry@7928} "org.springframework.context.annotation.internalAutowiredAnnotationProcessor" -> "Root bean: class [org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor]; scope=; abstract=false; lazyInit=false; autowireMode=0; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=null; initMethodName=null; destroyMethodName=null"
			//64 = {ConcurrentHashMap$MapEntry@7929} "spring.resources-org.springframework.boot.autoconfigure.web.ResourceProperties" -> "Generic bean: class [org.springframework.boot.autoconfigure.web.ResourceProperties]; scope=; abstract=false; lazyInit=false; autowireMode=0; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=null; initMethodName=null; destroyMethodName=null"
			//65 = {ConcurrentHashMap$MapEntry@7930} "org.springframework.boot.context.properties.ConfigurationBeanFactoryMetadata" -> "Generic bean: class [org.springframework.boot.context.properties.ConfigurationBeanFactoryMetadata]; scope=; abstract=false; lazyInit=false; autowireMode=0; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=null; initMethodName=null; destroyMethodName=null"
			//66 = {ConcurrentHashMap$MapEntry@7931} "springmvctheoryApplication" -> "Generic bean: class [com.lzkspace.springmvctheory.SpringmvctheoryApplication]; scope=singleton; abstract=false; lazyInit=false; autowireMode=0; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=null; initMethodName=null; destroyMethodName=null"
			//67 = {ConcurrentHashMap$MapEntry@7932} "org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration" -> "Generic bean: class [org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration]; scope=singleton; abstract=false; lazyInit=false; autowireMode=0; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=null; initMethodName=null; destroyMethodName=null"
			//68 = {ConcurrentHashMap$MapEntry@7933} "org.springframework.boot.autoconfigure.internalCachingMetadataReaderFactory" -> "Generic bean: class [org.springframework.boot.autoconfigure.SharedMetadataReaderFactoryContextInitializer$SharedMetadataReaderFactoryBean]; scope=; abstract=false; lazyInit=false; autowireMode=0; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=null; initMethodName=null; destroyMethodName=null"
			//69 = {ConcurrentHashMap$MapEntry@7934} "org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration$ParameterNamesModuleConfiguration" -> "Generic bean: class [org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration$ParameterNamesModuleConfiguration]; scope=singleton; abstract=false; lazyInit=false; autowireMode=0; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=null; initMethodName=null; destroyMethodName=null"
			//70 = {ConcurrentHashMap$MapEntry@7935} "mvcContentNegotiationManager" -> "Root bean: class [null]; scope=; abstract=false; lazyInit=false; autowireMode=3; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration$EnableWebMvcConfiguration; factoryMethodName=mvcContentNegotiationManager; initMethodName=null; destroyMethodName=(inferred); defined in class path resource [org/springframework/boot/autoconfigure/web/servlet/WebMvcAutoConfiguration$EnableWebMvcConfiguration.class]"
			//71 = {ConcurrentHashMap$MapEntry@7936} "objectNamingStrategy" -> "Root bean: class [null]; scope=; abstract=false; lazyInit=false; autowireMode=3; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=org.springframework.boot.autoconfigure.jmx.JmxAutoConfiguration; factoryMethodName=objectNamingStrategy; initMethodName=null; destroyMethodName=(inferred); defined in class path resource [org/springframework/boot/autoconfigure/jmx/JmxAutoConfiguration.class]"
			//72 = {ConcurrentHashMap$MapEntry@7937} "org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration" -> "Generic bean: class [org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration]; scope=singleton; abstract=false; lazyInit=false; autowireMode=0; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=null; initMethodName=null; destroyMethodName=null"
			//73 = {ConcurrentHashMap$MapEntry@7938} "errorAttributes" -> "Root bean: class [null]; scope=; abstract=false; lazyInit=false; autowireMode=3; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration; factoryMethodName=errorAttributes; initMethodName=null; destroyMethodName=(inferred); defined in class path resource [org/springframework/boot/autoconfigure/web/servlet/error/ErrorMvcAutoConfiguration.class]"
			//74 = {ConcurrentHashMap$MapEntry@7939} "httpRequestHandlerAdapter" -> "Root bean: class [null]; scope=; abstract=false; lazyInit=false; autowireMode=3; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration$EnableWebMvcConfiguration; factoryMethodName=httpRequestHandlerAdapter; initMethodName=null; destroyMethodName=(inferred); defined in class path resource [org/springframework/boot/autoconfigure/web/servlet/WebMvcAutoConfiguration$EnableWebMvcConfiguration.class]"
			//75 = {ConcurrentHashMap$MapEntry@7940} "beanNameHandlerMapping" -> "Root bean: class [null]; scope=; abstract=false; lazyInit=false; autowireMode=3; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration$EnableWebMvcConfiguration; factoryMethodName=beanNameHandlerMapping; initMethodName=null; destroyMethodName=(inferred); defined in class path resource [org/springframework/boot/autoconfigure/web/servlet/WebMvcAutoConfiguration$EnableWebMvcConfiguration.class]"
			//76 = {ConcurrentHashMap$MapEntry@7941} "spring.servlet.multipart-org.springframework.boot.autoconfigure.web.servlet.MultipartProperties" -> "Generic bean: class [org.springframework.boot.autoconfigure.web.servlet.MultipartProperties]; scope=; abstract=false; lazyInit=false; autowireMode=0; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=null; initMethodName=null; destroyMethodName=null"
			//77 = {ConcurrentHashMap$MapEntry@7942} "org.springframework.boot.autoconfigure.info.ProjectInfoAutoConfiguration" -> "Generic bean: class [org.springframework.boot.autoconfigure.info.ProjectInfoAutoConfiguration]; scope=singleton; abstract=false; lazyInit=false; autowireMode=0; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=null; initMethodName=null; destroyMethodName=null"
			//78 = {ConcurrentHashMap$MapEntry@7943} "org.springframework.context.annotation.internalCommonAnnotationProcessor" -> "Root bean: class [org.springframework.context.annotation.CommonAnnotationBeanPostProcessor]; scope=; abstract=false; lazyInit=false; autowireMode=0; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=null; initMethodName=null; destroyMethodName=null"
			//79 = {ConcurrentHashMap$MapEntry@7944} "org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryConfiguration$EmbeddedTomcat" -> "Generic bean: class [org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryConfiguration$EmbeddedTomcat]; scope=singleton; abstract=false; lazyInit=false; autowireMode=0; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=null; initMethodName=null; destroyMethodName=null"
			//80 = {ConcurrentHashMap$MapEntry@7945} "resourceHandlerMapping" -> "Root bean: class [null]; scope=; abstract=false; lazyInit=false; autowireMode=3; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration$EnableWebMvcConfiguration; factoryMethodName=resourceHandlerMapping; initMethodName=null; destroyMethodName=(inferred); defined in class path resource [org/springframework/boot/autoconfigure/web/servlet/WebMvcAutoConfiguration$EnableWebMvcConfiguration.class]"
			//81 = {ConcurrentHashMap$MapEntry@7946} "simpleControllerHandlerAdapter" -> "Root bean: class [null]; scope=; abstract=false; lazyInit=false; autowireMode=3; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration$EnableWebMvcConfiguration; factoryMethodName=simpleControllerHandlerAdapter; initMethodName=null; destroyMethodName=(inferred); defined in class path resource [org/springframework/boot/autoconfigure/web/servlet/WebMvcAutoConfiguration$EnableWebMvcConfiguration.class]"
			//82 = {ConcurrentHashMap$MapEntry@7947} "spring.http-org.springframework.boot.autoconfigure.http.HttpProperties" -> "Generic bean: class [org.springframework.boot.autoconfigure.http.HttpProperties]; scope=; abstract=false; lazyInit=false; autowireMode=0; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=null; initMethodName=null; destroyMethodName=null"
			//83 = {ConcurrentHashMap$MapEntry@7948} "parameterNamesModule" -> "Root bean: class [null]; scope=; abstract=false; lazyInit=false; autowireMode=3; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration$ParameterNamesModuleConfiguration; factoryMethodName=parameterNamesModule; initMethodName=null; destroyMethodName=(inferred); defined in class path resource [org/springframework/boot/autoconfigure/jackson/JacksonAutoConfiguration$ParameterNamesModuleConfiguration.class]"
			//84 = {ConcurrentHashMap$MapEntry@7949} "org.springframework.boot.autoconfigure.web.client.RestTemplateAutoConfiguration" -> "Generic bean: class [org.springframework.boot.autoconfigure.web.client.RestTemplateAutoConfiguration]; scope=singleton; abstract=false; lazyInit=false; autowireMode=0; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=null; initMethodName=null; destroyMethodName=null"
			//85 = {ConcurrentHashMap$MapEntry@7950} "hiddenHttpMethodFilter" -> "Root bean: class [null]; scope=; abstract=false; lazyInit=false; autowireMode=3; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration; factoryMethodName=hiddenHttpMethodFilter; initMethodName=null; destroyMethodName=(inferred); defined in class path resource [org/springframework/boot/autoconfigure/web/servlet/WebMvcAutoConfiguration.class]"
			//86 = {ConcurrentHashMap$MapEntry@7951} "org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration" -> "Generic bean: class [org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration]; scope=singleton; abstract=false; lazyInit=false; autowireMode=0; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=null; initMethodName=null; destroyMethodName=null"
			//87 = {ConcurrentHashMap$MapEntry@7952} "mvcValidator" -> "Root bean: class [null]; scope=; abstract=false; lazyInit=false; autowireMode=3; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration$EnableWebMvcConfiguration; factoryMethodName=mvcValidator; initMethodName=null; destroyMethodName=(inferred); defined in class path resource [org/springframework/boot/autoconfigure/web/servlet/WebMvcAutoConfiguration$EnableWebMvcConfiguration.class]"
			//88 = {ConcurrentHashMap$MapEntry@7953} "org.springframework.boot.autoconfigure.websocket.servlet.WebSocketServletAutoConfiguration$TomcatWebSocketConfiguration" -> "Generic bean: class [org.springframework.boot.autoconfigure.websocket.servlet.WebSocketServletAutoConfiguration$TomcatWebSocketConfiguration]; scope=singleton; abstract=false; lazyInit=false; autowireMode=0; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=null; initMethodName=null; destroyMethodName=null"
			//89 = {ConcurrentHashMap$MapEntry@7954} "org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration" -> "Generic bean: class [org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration]; scope=singleton; abstract=false; lazyInit=false; autowireMode=0; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=null; initMethodName=null; destroyMethodName=null"
			//90 = {ConcurrentHashMap$MapEntry@7955} "mvcResourceUrlProvider" -> "Root bean: class [null]; scope=; abstract=false; lazyInit=false; autowireMode=3; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration$EnableWebMvcConfiguration; factoryMethodName=mvcResourceUrlProvider; initMethodName=null; destroyMethodName=(inferred); defined in class path resource [org/springframework/boot/autoconfigure/web/servlet/WebMvcAutoConfiguration$EnableWebMvcConfiguration.class]"
			//91 = {ConcurrentHashMap$MapEntry@7956} "spring.task.execution-org.springframework.boot.autoconfigure.task.TaskExecutionProperties" -> "Generic bean: class [org.springframework.boot.autoconfigure.task.TaskExecutionProperties]; scope=; abstract=false; lazyInit=false; autowireMode=0; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=null; initMethodName=null; destroyMethodName=null"
			//92 = {ConcurrentHashMap$MapEntry@7957} "viewControllerHandlerMapping" -> "Root bean: class [null]; scope=; abstract=false; lazyInit=false; autowireMode=3; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration$EnableWebMvcConfiguration; factoryMethodName=viewControllerHandlerMapping; initMethodName=null; destroyMethodName=(inferred); defined in class path resource [org/springframework/boot/autoconfigure/web/servlet/WebMvcAutoConfiguration$EnableWebMvcConfiguration.class]"
			//93 = {ConcurrentHashMap$MapEntry@7958} todo 这里加个todo是为了引起注意，这个是项目里面加了@Bean的方法"myDispatcherServlet" -> "Root bean: class [null]; scope=; abstract=false; lazyInit=false; autowireMode=3; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=springmvctheoryApplication; factoryMethodName=myDispatcherServlet; initMethodName=null; destroyMethodName=(inferred); defined in com.lzkspace.springmvctheory.SpringmvctheoryApplication"
			//94 = {ConcurrentHashMap$MapEntry@7959} "dispatcherServlet" -> "Root bean: class [null]; scope=; abstract=false; lazyInit=false; autowireMode=3; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration$DispatcherServletConfiguration; factoryMethodName=dispatcherServlet; initMethodName=null; destroyMethodName=(inferred); defined in class path resource [org/springframework/boot/autoconfigure/web/servlet/DispatcherServletAutoConfiguration$DispatcherServletConfiguration.class]"
			//95 = {ConcurrentHashMap$MapEntry@7960} "org.springframework.boot.autoconfigure.AutoConfigurationPackages" -> "Generic bean: class [org.springframework.boot.autoconfigure.AutoConfigurationPackages$BasePackages]; scope=; abstract=false; lazyInit=false; autowireMode=0; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=null; initMethodName=null; destroyMethodName=null"
			//96 = {ConcurrentHashMap$MapEntry@7961} "org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration" -> "Generic bean: class [org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration]; scope=singleton; abstract=false; lazyInit=false; autowireMode=0; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=null; initMethodName=null; destroyMethodName=null"
			//97 = {ConcurrentHashMap$MapEntry@7962} "org.springframework.boot.context.properties.ConfigurationPropertiesBindingPostProcessor" -> "Generic bean: class [org.springframework.boot.context.properties.ConfigurationPropertiesBindingPostProcessor]; scope=; abstract=false; lazyInit=false; autowireMode=0; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=null; initMethodName=null; destroyMethodName=null"
			//98 = {ConcurrentHashMap$MapEntry@7963} "org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration" -> "Generic bean: class [org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration]; scope=singleton; abstract=false; lazyInit=false; autowireMode=0; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=null; initMethodName=null; destroyMethodName=null"
			//99 = {ConcurrentHashMap$MapEntry@7964} "org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration$WebMvcAutoConfigurationAdapter" -> "Generic bean: class [org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration$WebMvcAutoConfigurationAdapter]; scope=singleton; abstract=false; lazyInit=false; autowireMode=0; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=null; initMethodName=null; destroyMethodName=null"
			//100 = {ConcurrentHashMap$MapEntry@8067} "errorPageRegistrarBeanPostProcessor" -> "Root bean: class [org.springframework.boot.web.server.ErrorPageRegistrarBeanPostProcessor]; scope=; abstract=false; lazyInit=false; autowireMode=0; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=null; initMethodName=null; destroyMethodName=null"
			//101 = {ConcurrentHashMap$MapEntry@8068} "errorPageCustomizer" -> "Root bean: class [null]; scope=; abstract=false; lazyInit=false; autowireMode=3; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration; factoryMethodName=errorPageCustomizer; initMethodName=null; destroyMethodName=(inferred); defined in class path resource [org/springframework/boot/autoconfigure/web/servlet/error/ErrorMvcAutoConfiguration.class]"
			//102 = {ConcurrentHashMap$MapEntry@8069} "mvcConversionService" -> "Root bean: class [null]; scope=; abstract=false; lazyInit=false; autowireMode=3; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration$EnableWebMvcConfiguration; factoryMethodName=mvcConversionService; initMethodName=null; destroyMethodName=(inferred); defined in class path resource [org/springframework/boot/autoconfigure/web/servlet/WebMvcAutoConfiguration$EnableWebMvcConfiguration.class]"
			//103 = {ConcurrentHashMap$MapEntry@8070} "org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration$DefaultErrorViewResolverConfiguration" -> "Generic bean: class [org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration$DefaultErrorViewResolverConfiguration]; scope=singleton; abstract=false; lazyInit=false; autowireMode=0; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=null; initMethodName=null; destroyMethodName=null"
			//104 = {ConcurrentHashMap$MapEntry@8071} "tomcatWebServerFactoryCustomizer" -> "Root bean: class [null]; scope=; abstract=false; lazyInit=false; autowireMode=3; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=org.springframework.boot.autoconfigure.web.embedded.EmbeddedWebServerFactoryCustomizerAutoConfiguration$TomcatWebServerFactoryCustomizerConfiguration; factoryMethodName=tomcatWebServerFactoryCustomizer; initMethodName=null; destroyMethodName=(inferred); defined in class path resource [org/springframework/boot/autoconfigure/web/embedded/EmbeddedWebServerFactoryCustomizerAutoConfiguration$TomcatWebServerFactoryCustomizerConfiguration.class]"
			//105 = {ConcurrentHashMap$MapEntry@8072} "faviconHandlerMapping" -> "Root bean: class [null]; scope=; abstract=false; lazyInit=false; autowireMode=3; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration$WebMvcAutoConfigurationAdapter$FaviconConfiguration; factoryMethodName=faviconHandlerMapping; initMethodName=null; destroyMethodName=(inferred); defined in class path resource [org/springframework/boot/autoconfigure/web/servlet/WebMvcAutoConfiguration$WebMvcAutoConfigurationAdapter$FaviconConfiguration.class]"
			//106 = {ConcurrentHashMap$MapEntry@8073} "org.springframework.boot.autoconfigure.http.JacksonHttpMessageConvertersConfiguration" -> "Generic bean: class [org.springframework.boot.autoconfigure.http.JacksonHttpMessageConvertersConfiguration]; scope=singleton; abstract=false; lazyInit=false; autowireMode=0; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=null; initMethodName=null; destroyMethodName=null"
			//107 = {ConcurrentHashMap$MapEntry@8074} "mvcPathMatcher" -> "Root bean: class [null]; scope=; abstract=false; lazyInit=false; autowireMode=3; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration$EnableWebMvcConfiguration; factoryMethodName=mvcPathMatcher; initMethodName=null; destroyMethodName=(inferred); defined in class path resource [org/springframework/boot/autoconfigure/web/servlet/WebMvcAutoConfiguration$EnableWebMvcConfiguration.class]"
			//108 = {ConcurrentHashMap$MapEntry@8075} "handlerExceptionResolver" -> "Root bean: class [null]; scope=; abstract=false; lazyInit=false; autowireMode=3; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration$EnableWebMvcConfiguration; factoryMethodName=handlerExceptionResolver; initMethodName=null; destroyMethodName=(inferred); defined in class path resource [org/springframework/boot/autoconfigure/web/servlet/WebMvcAutoConfiguration$EnableWebMvcConfiguration.class]"
			//109 = {ConcurrentHashMap$MapEntry@8076} "basicErrorController" -> "Root bean: class [null]; scope=; abstract=false; lazyInit=false; autowireMode=3; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration; factoryMethodName=basicErrorController; initMethodName=null; destroyMethodName=(inferred); defined in class path resource [org/springframework/boot/autoconfigure/web/servlet/error/ErrorMvcAutoConfiguration.class]"
			//110 = {ConcurrentHashMap$MapEntry@8077} "org.springframework.boot.autoconfigure.http.codec.CodecsAutoConfiguration" -> "Generic bean: class [org.springframework.boot.autoconfigure.http.codec.CodecsAutoConfiguration]; scope=singleton; abstract=false; lazyInit=false; autowireMode=0; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=null; initMethodName=null; destroyMethodName=null"
			//111 = {ConcurrentHashMap$MapEntry@8078} "dispatcherServletRegistration" -> "Root bean: class [null]; scope=; abstract=false; lazyInit=false; autowireMode=3; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration$DispatcherServletRegistrationConfiguration; factoryMethodName=dispatcherServletRegistration; initMethodName=null; destroyMethodName=(inferred); defined in class path resource [org/springframework/boot/autoconfigure/web/servlet/DispatcherServletAutoConfiguration$DispatcherServletRegistrationConfiguration.class]"
			//112 = {ConcurrentHashMap$MapEntry@8079} "org.springframework.boot.autoconfigure.http.codec.CodecsAutoConfiguration$JacksonCodecConfiguration" -> "Generic bean: class [org.springframework.boot.autoconfigure.http.codec.CodecsAutoConfiguration$JacksonCodecConfiguration]; scope=singleton; abstract=false; lazyInit=false; autowireMode=0; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=null; initMethodName=null; destroyMethodName=null"
			//113 = {ConcurrentHashMap$MapEntry@8080} "tomcatServletWebServerFactory" -> "Root bean: class [null]; scope=; abstract=false; lazyInit=false; autowireMode=3; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryConfiguration$EmbeddedTomcat; factoryMethodName=tomcatServletWebServerFactory; initMethodName=null; destroyMethodName=(inferred); defined in class path resource [org/springframework/boot/autoconfigure/web/servlet/ServletWebServerFactoryConfiguration$EmbeddedTomcat.class]"
			//114 = {ConcurrentHashMap$MapEntry@8081} "org.springframework.boot.autoconfigure.http.JacksonHttpMessageConvertersConfiguration$MappingJackson2HttpMessageConverterConfiguration" -> "Generic bean: class [org.springframework.boot.autoconfigure.http.JacksonHttpMessageConvertersConfiguration$MappingJackson2HttpMessageConverterConfiguration]; scope=singleton; abstract=false; lazyInit=false; autowireMode=0; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=null; initMethodName=null; destroyMethodName=null"
			//115 = {ConcurrentHashMap$MapEntry@8082} "spring.jackson-org.springframework.boot.autoconfigure.jackson.JacksonProperties" -> "Generic bean: class [org.springframework.boot.autoconfigure.jackson.JacksonProperties]; scope=; abstract=false; lazyInit=false; autowireMode=0; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=null; initMethodName=null; destroyMethodName=null"
			//116 = {ConcurrentHashMap$MapEntry@8083} "error" -> "Root bean: class [null]; scope=; abstract=false; lazyInit=false; autowireMode=3; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration$WhitelabelErrorViewConfiguration; factoryMethodName=defaultErrorView; initMethodName=null; destroyMethodName=(inferred); defined in class path resource [org/springframework/boot/autoconfigure/web/servlet/error/ErrorMvcAutoConfiguration$WhitelabelErrorViewConfiguration.class]"
			//117 = {ConcurrentHashMap$MapEntry@8084} "org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration" -> "Generic bean: class [org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration]; scope=singleton; abstract=false; lazyInit=false; autowireMode=0; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=null; initMethodName=null; destroyMethodName=null"
			//117 = {ConcurrentHashMap$MapEntry@8084} "org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration" -> "Generic bean: class [org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration]; scope=singleton; abstract=false; lazyInit=false; autowireMode=0; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=null; initMethodName=null; destroyMethodName=null"
			//118 = {ConcurrentHashMap$MapEntry@8085} "org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration$JacksonObjectMapperConfiguration" -> "Generic bean: class [org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration$JacksonObjectMapperConfiguration]; scope=singleton; abstract=false; lazyInit=false; autowireMode=0; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=null; initMethodName=null; destroyMethodName=null"
			//119 = {ConcurrentHashMap$MapEntry@8086} "org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration$WhitelabelErrorViewConfiguration" -> "Generic bean: class [org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration$WhitelabelErrorViewConfiguration]; scope=singleton; abstract=false; lazyInit=false; autowireMode=0; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=null; initMethodName=null; destroyMethodName=null"
			//120 = {ConcurrentHashMap$MapEntry@8087} "org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration$DispatcherServletConfiguration" -> "Generic bean: class [org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration$DispatcherServletConfiguration]; scope=singleton; abstract=false; lazyInit=false; autowireMode=0; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=null; initMethodName=null; destroyMethodName=null"
			//121 = {ConcurrentHashMap$MapEntry@8088} "org.springframework.boot.autoconfigure.jmx.JmxAutoConfiguration" -> "Generic bean: class [org.springframework.boot.autoconfigure.jmx.JmxAutoConfiguration]; scope=singleton; abstract=false; lazyInit=false; autowireMode=0; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=null; factoryMethodName=null; initMethodName=null; destroyMethodName=null"
			//122 = {ConcurrentHashMap$MapEntry@8089} "mvcViewResolver" -> "Root bean: class [null]; scope=; abstract=false; lazyInit=false; autowireMode=3; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration$EnableWebMvcConfiguration; factoryMethodName=mvcViewResolver; initMethodName=null; destroyMethodName=(inferred); defined in class path resource [org/springframework/boot/autoconfigure/web/servlet/WebMvcAutoConfiguration$EnableWebMvcConfiguration.class]"
			//123 = {ConcurrentHashMap$MapEntry@8090} "defaultValidator" -> "Root bean: class [org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration]; scope=; abstract=false; lazyInit=false; autowireMode=3; dependencyCheck=0; autowireCandidate=true; primary=true; factoryBeanName=null; factoryMethodName=defaultValidator; initMethodName=null; destroyMethodName=(inferred); defined in class path resource [org/springframework/boot/autoconfigure/validation/ValidationAutoConfiguration.class]"
			//124 = {ConcurrentHashMap$MapEntry@8091} "welcomePageHandlerMapping" -> "Root bean: class [null]; scope=; abstract=false; lazyInit=false; autowireMode=3; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration$WebMvcAutoConfigurationAdapter; factoryMethodName=welcomePageHandlerMapping; initMethodName=null; destroyMethodName=(inferred); defined in class path resource [org/springframework/boot/autoconfigure/web/servlet/WebMvcAutoConfiguration$WebMvcAutoConfigurationAdapter.class]"
			//125 = {ConcurrentHashMap$MapEntry@8092} "mvcUriComponentsContributor" -> "Root bean: class [null]; scope=; abstract=false; lazyInit=false; autowireMode=3; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration$EnableWebMvcConfiguration; factoryMethodName=mvcUriComponentsContributor; initMethodName=null; destroyMethodName=(inferred); defined in class path resource [org/springframework/boot/autoconfigure/web/servlet/WebMvcAutoConfiguration$EnableWebMvcConfiguration.class]"
			//126 = {ConcurrentHashMap$MapEntry@8093} "jacksonObjectMapper" -> "Root bean: class [null]; scope=; abstract=false; lazyInit=false; autowireMode=3; dependencyCheck=0; autowireCandidate=true; primary=true; factoryBeanName=org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration$JacksonObjectMapperConfiguration; factoryMethodName=jacksonObjectMapper; initMethodName=null; destroyMethodName=(inferred); defined in class path resource [org/springframework/boot/autoconfigure/jackson/JacksonAutoConfiguration$JacksonObjectMapperConfiguration.class]"
			//127 = {ConcurrentHashMap$MapEntry@8094} "taskExecutorBuilder" -> "Root bean: class [null]; scope=; abstract=false; lazyInit=false; autowireMode=3; dependencyCheck=0; autowireCandidate=true; primary=false; factoryBeanName=org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration; factoryMethodName=taskExecutorBuilder; initMethodName=null; destroyMethodName=(inferred); defined in class path resource [org/springframework/boot/autoconfigure/task/TaskExecutionAutoConfiguration.class]"
			this.reader.loadBeanDefinitions(configClasses);
			// 然后将configClasses定义为已解析,注意，这里是一个set，而beanDefinitionMap中，很多是同一个类的，所以alreadyParsed只有44个
			alreadyParsed.addAll(configClasses);

			// 清楚candidates
			candidates.clear();
			if (registry.getBeanDefinitionCount() > candidateNames.length) {
				// 这里获取的就是上面128个map的key值
				String[] newCandidateNames = registry.getBeanDefinitionNames();
				// 原先的candidateNames的内容为：
				//0 = "org.springframework.context.annotation.internalConfigurationAnnotationProcessor"
				//1 = "org.springframework.context.annotation.internalAutowiredAnnotationProcessor"
				//2 = "org.springframework.context.annotation.internalCommonAnnotationProcessor"
				//3 = "org.springframework.context.event.internalEventListenerProcessor"
				//4 = "org.springframework.context.event.internalEventListenerFactory"
				//5 = "springmvctheoryApplication"
				//6 = "org.springframework.boot.autoconfigure.internalCachingMetadataReaderFactory"
				Set<String> oldCandidateNames = new HashSet<>(Arrays.asList(candidateNames));
				Set<String> alreadyParsedClasses = new HashSet<>();
				for (ConfigurationClass configurationClass : alreadyParsed) {
					// 将44个中configurationClass加入到alreadyParsedClasses中
					alreadyParsedClasses.add(configurationClass.getMetadata().getClassName());
				}
				// 这里代码容易看，就是遍历后构造BeanDefinitionHolder放到candidates中
				for (String candidateName : newCandidateNames) {
					if (!oldCandidateNames.contains(candidateName)) {
						BeanDefinition bd = registry.getBeanDefinition(candidateName);
						if (ConfigurationClassUtils.checkConfigurationClassCandidate(bd, this.metadataReaderFactory) &&
								!alreadyParsedClasses.contains(bd.getBeanClassName())) {// 首次调用进来，由于不满足调教，最终candidates的size为0
							candidates.add(new BeanDefinitionHolder(bd, candidateName));
						}
					}
				}
				candidateNames = newCandidateNames;
			}
		}
		while (!candidates.isEmpty());

		// Register the ImportRegistry as a bean in order to support ImportAware @Configuration classes
		// 首次断点进入这里，这里是将parser之前放在importStack中的几个ImportRegistry放到singletonObjects和manualSingletonNames中，如下：
		// 0 = {ConcurrentHashMap$MapEntry@15347} "org.springframework.context.annotation.internalConfigurationAnnotationProcessor" ->
		//1 = {ConcurrentHashMap$MapEntry@15348} "springApplicationArguments" ->
		//2 = {ConcurrentHashMap$MapEntry@15349} "autoConfigurationReport" ->
		//3 = {ConcurrentHashMap$MapEntry@15350} "systemEnvironment" -> " size = 37"
		//4 = {ConcurrentHashMap$MapEntry@15351} "org.springframework.boot.autoconfigure.condition.BeanTypeRegistry" ->
		//todo 这里加todo，是为了引起注意，这个就是新加进去的 5 = {ConcurrentHashMap$MapEntry@15352} "org.springframework.context.annotation.ConfigurationClassPostProcessor.importRegistry" -> " size = 0"
		//6 = {ConcurrentHashMap$MapEntry@15353} "org.springframework.boot.context.ContextIdApplicationContextInitializer$ContextId" ->
		//7 = {ConcurrentHashMap$MapEntry@15354} "org.springframework.boot.autoconfigure.internalCachingMetadataReaderFactory" ->
		//8 = {ConcurrentHashMap$MapEntry@15355} "environment" -> "StandardServletEnvironment {activeProfiles=[], defaultProfiles=[default], propertySources=[ConfigurationPropertySourcesPropertySource {name='configurationProperties'}, StubPropertySource {name='servletConfigInitParams'}, StubPropertySource {name='servletContextInitParams'}, MapPropertySource {name='systemProperties'}, OriginAwareSystemEnvironmentPropertySource {name='systemEnvironment'}, RandomValuePropertySource {name='random'}]}"
		//9 = {ConcurrentHashMap$MapEntry@15356} "springBootLoggingSystem" ->
		//10 = {ConcurrentHashMap$MapEntry@15357} "systemProperties" -> " size = 64"
		//11 = {ConcurrentHashMap$MapEntry@15358} "springBootBanner" ->

		// 0 = "autoConfigurationReport"
		//1 = "org.springframework.boot.context.ContextIdApplicationContextInitializer$ContextId"
		//2 = "springApplicationArguments"
		//3 = "springBootBanner"
		//4 = "springBootLoggingSystem"
		//5 = "environment"
		//6 = "systemProperties"
		//7 = "systemEnvironment"
		//8 = todo 同上todo "org.springframework.context.annotation.ConfigurationClassPostProcessor.importRegistry"
		if (sbr != null && !sbr.containsSingleton(IMPORT_REGISTRY_BEAN_NAME)) {
			sbr.registerSingleton(IMPORT_REGISTRY_BEAN_NAME, parser.getImportRegistry());
		}

		if (this.metadataReaderFactory instanceof CachingMetadataReaderFactory) {
			// Clear cache in externally provided MetadataReaderFactory; this is a no-op
			// for a shared cache since it'll be cleared by the ApplicationContext.
			((CachingMetadataReaderFactory) this.metadataReaderFactory).clearCache();
		}
	}

	/**
	 * Post-processes a BeanFactory in search of Configuration class BeanDefinitions;
	 * any candidates are then enhanced by a {@link ConfigurationClassEnhancer}.
	 * Candidate status is determined by BeanDefinition attribute metadata.
	 * @see ConfigurationClassEnhancer
	 */
	public void enhanceConfigurationClasses(ConfigurableListableBeanFactory beanFactory) {
		Map<String, AbstractBeanDefinition> configBeanDefs = new LinkedHashMap<>();
		for (String beanName : beanFactory.getBeanDefinitionNames()) {
			BeanDefinition beanDef = beanFactory.getBeanDefinition(beanName);
			Object configClassAttr = beanDef.getAttribute(ConfigurationClassUtils.CONFIGURATION_CLASS_ATTRIBUTE);
			MethodMetadata methodMetadata = null;
			if (beanDef instanceof AnnotatedBeanDefinition) {
				methodMetadata = ((AnnotatedBeanDefinition) beanDef).getFactoryMethodMetadata();
			}
			if ((configClassAttr != null || methodMetadata != null) && beanDef instanceof AbstractBeanDefinition) {
				// Configuration class (full or lite) or a configuration-derived @Bean method
				// -> resolve bean class at this point...
				AbstractBeanDefinition abd = (AbstractBeanDefinition) beanDef;
				if (!abd.hasBeanClass()) {
					try {
						abd.resolveBeanClass(this.beanClassLoader);
					}
					catch (Throwable ex) {
						throw new IllegalStateException(
								"Cannot load configuration class: " + beanDef.getBeanClassName(), ex);
					}
				}
			}
			if (ConfigurationClassUtils.CONFIGURATION_CLASS_FULL.equals(configClassAttr)) {
				if (!(beanDef instanceof AbstractBeanDefinition)) {
					throw new BeanDefinitionStoreException("Cannot enhance @Configuration bean definition '" +
							beanName + "' since it is not stored in an AbstractBeanDefinition subclass");
				}
				else if (logger.isInfoEnabled() && beanFactory.containsSingleton(beanName)) {
					logger.info("Cannot enhance @Configuration bean definition '" + beanName +
							"' since its singleton instance has been created too early. The typical cause " +
							"is a non-static @Bean method with a BeanDefinitionRegistryPostProcessor " +
							"return type: Consider declaring such methods as 'static'.");
				}
				configBeanDefs.put(beanName, (AbstractBeanDefinition) beanDef);
			}
		}
		if (configBeanDefs.isEmpty()) {
			// nothing to enhance -> return immediately
			return;
		}

		ConfigurationClassEnhancer enhancer = new ConfigurationClassEnhancer();
		for (Map.Entry<String, AbstractBeanDefinition> entry : configBeanDefs.entrySet()) {
			AbstractBeanDefinition beanDef = entry.getValue();
			// If a @Configuration class gets proxied, always proxy the target class
			beanDef.setAttribute(AutoProxyUtils.PRESERVE_TARGET_CLASS_ATTRIBUTE, Boolean.TRUE);
			// Set enhanced subclass of the user-specified bean class
			Class<?> configClass = beanDef.getBeanClass();
			Class<?> enhancedClass = enhancer.enhance(configClass, this.beanClassLoader);
			if (configClass != enhancedClass) {
				if (logger.isTraceEnabled()) {
					logger.trace(String.format("Replacing bean definition '%s' existing class '%s' with " +
							"enhanced class '%s'", entry.getKey(), configClass.getName(), enhancedClass.getName()));
				}
				beanDef.setBeanClass(enhancedClass);
			}
		}
	}


	private static class ImportAwareBeanPostProcessor extends InstantiationAwareBeanPostProcessorAdapter {

		private final BeanFactory beanFactory;

		public ImportAwareBeanPostProcessor(BeanFactory beanFactory) {
			this.beanFactory = beanFactory;
		}

		@Override
		public PropertyValues postProcessProperties(@Nullable PropertyValues pvs, Object bean, String beanName) {
			// Inject the BeanFactory before AutowiredAnnotationBeanPostProcessor's
			// postProcessProperties method attempts to autowire other configuration beans.
			if (bean instanceof EnhancedConfiguration) {
				((EnhancedConfiguration) bean).setBeanFactory(this.beanFactory);
			}
			return pvs;
		}

		@Override
		public Object postProcessBeforeInitialization(Object bean, String beanName) {
			if (bean instanceof ImportAware) {
				ImportRegistry ir = this.beanFactory.getBean(IMPORT_REGISTRY_BEAN_NAME, ImportRegistry.class);
				AnnotationMetadata importingClass = ir.getImportingClassFor(bean.getClass().getSuperclass().getName());
				if (importingClass != null) {
					((ImportAware) bean).setImportMetadata(importingClass);
				}
			}
			return bean;
		}
	}

}
