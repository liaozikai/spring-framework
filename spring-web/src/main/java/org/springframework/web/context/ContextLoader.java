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

package org.springframework.web.context;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextException;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.GenericTypeResolver;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.lang.Nullable;
import org.springframework.util.ClassUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

import javax.servlet.ServletContext;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Performs the actual initialization work for the root application context.
 * Called by {@link ContextLoaderListener}.
 * 对于根应用上下文执行真正的初始化工作，被上下文加载监听器调用
 * <p>Looks for a {@link #CONTEXT_CLASS_PARAM "contextClass"} parameter at the
 * {@code web.xml} context-param level to specify the context class type, falling
 * back to {@link org.springframework.web.context.support.XmlWebApplicationContext}
 * if not found. With the default ContextLoader implementation, any context class
 * specified needs to implement the {@link ConfigurableWebApplicationContext} interface.
 * 在web.xml上下文参数级别查找CONTEXT_CLASS_PARAM“ contextClass”参数以指定上下文类类型，如果未找到，
 * 则返回org.springframework.web.context.support.XmlWebApplicationContext。
 * 使用默认的ContextLoader实现，指定的任何上下文类都需要实现ConfigurableWebApplicationContext接口。
 *
 * <p>Processes a {@link #CONFIG_LOCATION_PARAM "contextConfigLocation"} context-param
 * and passes its value to the context instance, parsing it into potentially multiple
 * file paths which can be separated by any number of commas and spaces, e.g.
 * "WEB-INF/applicationContext1.xml, WEB-INF/applicationContext2.xml".
 * 处理{@link #CONFIG_LOCATION_PARAM“ contextConfigLocation”}上下文参数，并将其值传递到上下文实例，
 * 将其解析为可能由多个逗号和空格分隔的多个文件路径，例如 “ WEB-INF / applicationContext1.xml，
 * WEB-INF / applicationContext2.xml”。
 * Ant-style path patterns are supported as well, e.g.
 * "WEB-INF/*Context.xml,WEB-INF/spring*.xml" or "WEB-INF/&#42;&#42;/*Context.xml".
 * If not explicitly specified, the context implementation is supposed to use a
 * default location (with XmlWebApplicationContext: "/WEB-INF/applicationContext.xml").
 *
 *
 * <p>Note: In case of multiple config locations, later bean definitions will
 * override ones defined in previously loaded files, at least when using one of
 * Spring's default ApplicationContext implementations. This can be leveraged
 * to deliberately override certain bean definitions via an extra XML file.
 * 注意：如果有多个配置位置，至少在使用Spring的默认ApplicationContext实现之一时，
 * 以后的bean定义将覆盖先前加载的文件中定义的定义。 可以利用它来通过一个额外的XML文件有意覆盖某些bean定义。
 *
 * <p>Above and beyond loading the root application context, this class can optionally
 * load or obtain and hook up a shared parent context to the root application context.
 * See the {@link #loadParentContext(ServletContext)} method for more information.
 * 除了加载根应用程序上下文之外，此类还可以选择加载或获取并将共享的父上下文挂钩到根应用程序上下文。
 * 有关更多信息，请参见{@link #loadParentContext（ServletContext）}方法。
 * <p>As of Spring 3.1, {@code ContextLoader} supports injecting the root web
 * application context via the {@link #ContextLoader(WebApplicationContext)}
 * constructor, allowing for programmatic configuration in Servlet 3.0+ environments.
 * See {@link org.springframework.web.WebApplicationInitializer} for usage examples.
 *
 * @author Juergen Hoeller
 * @author Colin Sampaleanu
 * @author Sam Brannen
 * @since 17.02.2003
 * @see ContextLoaderListener
 * @see ConfigurableWebApplicationContext
 * @see org.springframework.web.context.support.XmlWebApplicationContext
 */
public class ContextLoader {

	/**
	 * Config param for the root WebApplicationContext id,
	 * to be used as serialization id for the underlying BeanFactory: {@value}.
	 * web应用上下文id的配置参数，用作基础BeanFactory的序列化ID：value。
	 */
	public static final String CONTEXT_ID_PARAM = "contextId";

	/**
	 * Name of servlet context parameter (i.e., {@value}) that can specify the
	 * config location for the root context, falling back to the implementation's
	 * default otherwise.
	 * servelt上下文参数名称，能够指定根上下文的配置位置，否则返回到实现的默认值。
	 * @see org.springframework.web.context.support.XmlWebApplicationContext#DEFAULT_CONFIG_LOCATION
	 */
	public static final String CONFIG_LOCATION_PARAM = "contextConfigLocation";

	/**
	 * Config param for the root WebApplicationContext implementation class to use: {@value}.
	 * @see #determineContextClass(ServletContext)
	 */
	public static final String CONTEXT_CLASS_PARAM = "contextClass";

	/**
	 * Config param for {@link ApplicationContextInitializer} classes to use
	 * for initializing the root web application context: {@value}.
	 * @see #customizeContext(ServletContext, ConfigurableWebApplicationContext)
	 */
	public static final String CONTEXT_INITIALIZER_CLASSES_PARAM = "contextInitializerClasses";

	/**
	 * Config param for global {@link ApplicationContextInitializer} classes to use
	 * for initializing all web application contexts in the current application: {@value}.
	 * 全局ApplicationContextInitializer类的配置参数，用于初始化当前应用程序中的
	 * 所有Web应用程序上下文。
	 * @see #customizeContext(ServletContext, ConfigurableWebApplicationContext)
	 */
	public static final String GLOBAL_INITIALIZER_CLASSES_PARAM = "globalInitializerClasses";

	/**
	 * Any number of these characters are considered delimiters between
	 * multiple values in a single init-param String value.
	 */
	private static final String INIT_PARAM_DELIMITERS = ",; \t\n";

	/**
	 * Name of the class path resource (relative to the ContextLoader class)
	 * that defines ContextLoader's default strategy names.
	 */
	private static final String DEFAULT_STRATEGIES_PATH = "ContextLoader.properties";


	private static final Properties defaultStrategies;

	static {
		// Load default strategy implementations from properties file.
		// This is currently strictly internal and not meant to be customized
		// by application developers.
		try {
			ClassPathResource resource = new ClassPathResource(DEFAULT_STRATEGIES_PATH, ContextLoader.class);
			defaultStrategies = PropertiesLoaderUtils.loadProperties(resource);
		}
		catch (IOException ex) {
			throw new IllegalStateException("Could not load 'ContextLoader.properties': " + ex.getMessage());
		}
	}


	/**
	 * Map from (thread context) ClassLoader to corresponding 'current' WebApplicationContext.
	 */
	private static final Map<ClassLoader, WebApplicationContext> currentContextPerThread =
			new ConcurrentHashMap<>(1);

	/**
	 * The 'current' WebApplicationContext, if the ContextLoader class is
	 * deployed in the web app ClassLoader itself.
	 */
	@Nullable
	private static volatile WebApplicationContext currentContext;


	/**
	 * The root WebApplicationContext instance that this loader manages.
	 * 该加载器管理的根WebApplicationContext实例。
	 */
	@Nullable
	private WebApplicationContext context;

	/** Actual ApplicationContextInitializer instances to apply to the context. */
	// 实际的ApplicationContextInitializer实例要应用于上下文。
	private final List<ApplicationContextInitializer<ConfigurableApplicationContext>> contextInitializers =
			new ArrayList<>();


	/**
	 * Create a new {@code ContextLoader} that will create a web application context
	 * based on the "contextClass" and "contextConfigLocation" servlet context-params.
	 * See class-level documentation for details on default values for each.
	 * <p>This constructor is typically used when declaring the {@code
	 * ContextLoaderListener} subclass as a {@code <listener>} within {@code web.xml}, as
	 * a no-arg constructor is required.
	 * <p>The created application context will be registered into the ServletContext under
	 * the attribute name {@link WebApplicationContext#ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE}
	 * and subclasses are free to call the {@link #closeWebApplicationContext} method on
	 * container shutdown to close the application context.
	 * @see #ContextLoader(WebApplicationContext)
	 * @see #initWebApplicationContext(ServletContext)
	 * @see #closeWebApplicationContext(ServletContext)
	 */
	public ContextLoader() {
	}

	/**
	 * Create a new {@code ContextLoader} with the given application context. This
	 * constructor is useful in Servlet 3.0+ environments where instance-based
	 * registration of listeners is possible through the {@link ServletContext#addListener}
	 * API.
	 * <p>The context may or may not yet be {@linkplain
	 * ConfigurableApplicationContext#refresh() refreshed}. If it (a) is an implementation
	 * of {@link ConfigurableWebApplicationContext} and (b) has <strong>not</strong>
	 * already been refreshed (the recommended approach), then the following will occur:
	 * <ul>
	 * <li>If the given context has not already been assigned an {@linkplain
	 * ConfigurableApplicationContext#setId id}, one will be assigned to it</li>
	 * <li>{@code ServletContext} and {@code ServletConfig} objects will be delegated to
	 * the application context</li>
	 * <li>{@link #customizeContext} will be called</li>
	 * <li>Any {@link ApplicationContextInitializer ApplicationContextInitializers} specified through the
	 * "contextInitializerClasses" init-param will be applied.</li>
	 * <li>{@link ConfigurableApplicationContext#refresh refresh()} will be called</li>
	 * </ul>
	 * If the context has already been refreshed or does not implement
	 * {@code ConfigurableWebApplicationContext}, none of the above will occur under the
	 * assumption that the user has performed these actions (or not) per his or her
	 * specific needs.
	 * <p>See {@link org.springframework.web.WebApplicationInitializer} for usage examples.
	 * <p>In any case, the given application context will be registered into the
	 * ServletContext under the attribute name {@link
	 * WebApplicationContext#ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE} and subclasses are
	 * free to call the {@link #closeWebApplicationContext} method on container shutdown
	 * to close the application context.
	 * @param context the application context to manage
	 * @see #initWebApplicationContext(ServletContext)
	 * @see #closeWebApplicationContext(ServletContext)
	 */
	public ContextLoader(WebApplicationContext context) {
		this.context = context;
	}


	/**
	 * Specify which {@link ApplicationContextInitializer} instances should be used
	 * to initialize the application context used by this {@code ContextLoader}.
	 * @since 4.2
	 * @see #configureAndRefreshWebApplicationContext
	 * @see #customizeContext
	 */
	@SuppressWarnings("unchecked")
	public void setContextInitializers(@Nullable ApplicationContextInitializer<?>... initializers) {
		if (initializers != null) {
			for (ApplicationContextInitializer<?> initializer : initializers) {
				this.contextInitializers.add((ApplicationContextInitializer<ConfigurableApplicationContext>) initializer);
			}
		}
	}


	/**
	 * Initialize Spring's web application context for the given servlet context,
	 * using the application context provided at construction time, or creating a new one
	 * according to the "{@link #CONTEXT_CLASS_PARAM contextClass}" and
	 * "{@link #CONFIG_LOCATION_PARAM contextConfigLocation}" context-params.
	 * 对于给定的servelt上下文来初始化spring web应用上下文，使用在构造时间提供的应用上下文，
	 * 或者通过contextClass和contextConfigLocation上下文参数来创建一个新的web应用上下文
	 * @param servletContext current servlet context
	 * @return the new WebApplicationContext
	 * @see #ContextLoader(WebApplicationContext)
	 * @see #CONTEXT_CLASS_PARAM
	 * @see #CONFIG_LOCATION_PARAM
	 */
	public WebApplicationContext initWebApplicationContext(ServletContext servletContext) {
		if (servletContext.getAttribute(WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE) != null) {
			// 如果之前已经设置过该属性，则会报错。所以在web.xml中不能设置多个ContextLoader*(一般我们是设置一个ContextLoaderListener)
			throw new IllegalStateException(
					"Cannot initialize context because there is already a root application context present - " +
					"check whether you have multiple ContextLoader* definitions in your web.xml!");
		}

		servletContext.log("Initializing Spring root WebApplicationContext");
		Log logger = LogFactory.getLog(ContextLoader.class);
		if (logger.isInfoEnabled()) {
			logger.info("Root WebApplicationContext: initialization started");
		}
		long startTime = System.currentTimeMillis();

		try {
			// Store context in local instance variable, to guarantee that
			// it is available on ServletContext shutdown.
			// 在本地实例变量保存上下文，以确保它在ServletContext关闭时可用
			if (this.context == null) {
				// WebApplicationContext是ConfigurableWebApplicationContext的父接口
				this.context = createWebApplicationContext(servletContext);
			}
			if (this.context instanceof ConfigurableWebApplicationContext) {
				ConfigurableWebApplicationContext cwac = (ConfigurableWebApplicationContext) this.context;
				if (!cwac.isActive()) { // 该上下文还没刷新的话，则执行下面的内容
					// The context has not yet been refreshed -> provide services such as
					// setting the parent context, setting the application context id, etc
					// 上下文尚未刷新->提供诸如设置父上下文，设置应用程序上下文ID等服务。
					if (cwac.getParent() == null) {
						// The context instance was injected without an explicit parent ->
						// determine parent for root web application context, if any.
						// 上下文实例是在没有显式父级的情况下注入的->确定根Web应用程序上下文的父级（如果有）。
						ApplicationContext parent = loadParentContext(servletContext); // 默认实现返回null，子类可重写
						cwac.setParent(parent);
					}
					// 到这里，cwac是ConfigurableWebApplicationContext实例，而servletContext是ApplicationContext实例
					configureAndRefreshWebApplicationContext(cwac, servletContext);
				}
			}
			servletContext.setAttribute(WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE, this.context);

			ClassLoader ccl = Thread.currentThread().getContextClassLoader();
			if (ccl == ContextLoader.class.getClassLoader()) {
				currentContext = this.context;
			}
			else if (ccl != null) {
				currentContextPerThread.put(ccl, this.context);
			}

			if (logger.isInfoEnabled()) {
				long elapsedTime = System.currentTimeMillis() - startTime;
				logger.info("Root WebApplicationContext initialized in " + elapsedTime + " ms");
			}

			return this.context;
		}
		catch (RuntimeException | Error ex) {
			logger.error("Context initialization failed", ex);
			servletContext.setAttribute(WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE, ex);
			throw ex;
		}
	}

	/**
	 * Instantiate the root WebApplicationContext for this loader, either the
	 * default context class or a custom context class if specified.
	 * <p>This implementation expects custom contexts to implement the
	 * {@link ConfigurableWebApplicationContext} interface.
	 * Can be overridden in subclasses.
	 * 通过该加载器实例化根web应用程序上下文，或者默认的上下文类或者指定的自定义上下文类。
	 * 此实现期望自定义上下文能够实现ConfigurableWebApplicationContext接口。
	 * <p>In addition, {@link #customizeContext} gets called prior to refreshing the
	 * context, allowing subclasses to perform custom modifications to the context.
	 * 另外，在刷新上下文之前会调用customizeContext，从而允许子类对上下文执行自定义修改。
	 * @param sc current servlet context
	 * @return the root WebApplicationContext
	 * @see ConfigurableWebApplicationContext
	 */
	protected WebApplicationContext createWebApplicationContext(ServletContext sc) {
		Class<?> contextClass = determineContextClass(sc); // 获取XmlWebApplicationContext类实例
		// 从XmlWebApplicationContext的父类实现了ConfigurableWebApplicationContext接口，故而为其接口
		if (!ConfigurableWebApplicationContext.class.isAssignableFrom(contextClass)) {
			throw new ApplicationContextException("Custom context class [" + contextClass.getName() +
					"] is not of type [" + ConfigurableWebApplicationContext.class.getName() + "]");
		}
		// 通过上下文类构造 可配置的web应用上下文
		return (ConfigurableWebApplicationContext) BeanUtils.instantiateClass(contextClass);
	}

	/**
	 * Return the WebApplicationContext implementation class to use, either the
	 * default XmlWebApplicationContext or a custom context class if specified.
	 * @param servletContext current servlet context
	 * @return the WebApplicationContext implementation class to use
	 * @see #CONTEXT_CLASS_PARAM
	 * @see org.springframework.web.context.support.XmlWebApplicationContext
	 */
	protected Class<?> determineContextClass(ServletContext servletContext) {
		// 获取上下文类名称,也就是获取web.xml中param-name值为contextClass的属性，一般我们在web.xml不设置
		String contextClassName = servletContext.getInitParameter(CONTEXT_CLASS_PARAM);
		if (contextClassName != null) { // 上下文类名称不为空
			try {
				// 通过默认的类加载器来实例化上下文类
				return ClassUtils.forName(contextClassName, ClassUtils.getDefaultClassLoader());
			}
			catch (ClassNotFoundException ex) {
				throw new ApplicationContextException(
						"Failed to load custom context class [" + contextClassName + "]", ex);
			}
		}
		else {// 上下文类名称为空，获取web应用上下文的名称来获取上下文类名称
			// defaultStrategies中指向的是ContextLoader.properties文件，故而通过
			// key为WebApplicationContext.class.getName()获得值为
			// org.springframework.web.context.support.XmlWebApplicationContext
			contextClassName = defaultStrategies.getProperty(WebApplicationContext.class.getName());
			try {// 返回XmlWebApplicationContext类实例
				return ClassUtils.forName(contextClassName, ContextLoader.class.getClassLoader());
			}
			catch (ClassNotFoundException ex) {
				throw new ApplicationContextException(
						"Failed to load default context class [" + contextClassName + "]", ex);
			}
		}
	}

	protected void configureAndRefreshWebApplicationContext(ConfigurableWebApplicationContext wac, ServletContext sc) {
		if (ObjectUtils.identityToString(wac).equals(wac.getId())) {
			// The application context id is still set to its original default value
			// -> assign a more useful id based on available information
			// 应用程序上下文ID仍设置为其原始默认值->根据可用信息分配一个更有用的ID
			// 如果web.xml没有设置id的话，那么就生成一个
			String idParam = sc.getInitParameter(CONTEXT_ID_PARAM);
			if (idParam != null) {
				wac.setId(idParam);
			}
			else {
				// Generate default id... 如果没有配置，则生成默认id
				wac.setId(ConfigurableWebApplicationContext.APPLICATION_CONTEXT_ID_PREFIX +
						ObjectUtils.getDisplayString(sc.getContextPath()));
			}
		}

		// servletContext是ConfigurableWebApplicationContext的一个属性，而
		// web.xml中的内容是通过servletContext来获取的
		wac.setServletContext(sc);
		String configLocationParam = sc.getInitParameter(CONFIG_LOCATION_PARAM);
		if (configLocationParam != null) {// 如果配置位置参数不为空，则设置
			wac.setConfigLocation(configLocationParam);
		}

		// The wac environment's #initPropertySources will be called in any case when the context
		// is refreshed; do it eagerly here to ensure servlet property sources are in place for
		// use in any post-processing or initialization that occurs below prior to #refresh
		// 在刷新上下文的任何情况下，都会调用wac环境的#initPropertySources。
		// 请务必在此处急于执行此操作，以确保servlet属性源已准备就绪，可用于在#refresh之前发生的任何后处理或初始化中
		// 若我们在web.xml没有特殊配置，则wac.getEnviroment方法就是ConfigurableWebEnvironment的实例
		ConfigurableEnvironment env = wac.getEnvironment();
		if (env instanceof ConfigurableWebEnvironment) {
			// 注意servletConfig初始为null，只有sc在起作用
			// 初始化propertySources todo 有点搞不明白的是，里面的propertySources什么时候被设置进去的，有机会就断点试下？ 已解决
			// 这里有个重点，在实现构造方法时，用的是AbstractEnvironment的构造方法，里面调用了StandardServletEnvironment的customizePropertySources方法
			// 所以设置了PropertySources。从这里可以看出，很多无缘无故出现的属性，很大可能是采取设计模式让父类来设置属性，要慢慢找才看得出来
			((ConfigurableWebEnvironment) env).initPropertySources(sc, null);
		}

		// 定制上下文，如果web.xml没怎么设置，这里好像没什么用
		// 断点进来，这里的确什么都没做
		customizeContext(sc, wac);
		// 更新wac，这个才是重中之重。这里做的是spring 容器的初始化，下面就不去研究，这里可以看专门研究spring的代码
		wac.refresh();
	}

	/**
	 * Customize the {@link ConfigurableWebApplicationContext} created by this
	 * ContextLoader after config locations have been supplied to the context
	 * but before the context is <em>refreshed</em>.
	 * <p>The default implementation {@linkplain #determineContextInitializerClasses(ServletContext)
	 * determines} what (if any) context initializer classes have been specified through
	 * {@linkplain #CONTEXT_INITIALIZER_CLASSES_PARAM context init parameters} and
	 * {@linkplain ApplicationContextInitializer#initialize invokes each} with the
	 * given web application context.
	 * <p>Any {@code ApplicationContextInitializers} implementing
	 * {@link org.springframework.core.Ordered Ordered} or marked with @{@link
	 * org.springframework.core.annotation.Order Order} will be sorted appropriately.
	 * @param sc the current servlet context
	 * @param wac the newly created application context
	 * @see #CONTEXT_INITIALIZER_CLASSES_PARAM
	 * @see ApplicationContextInitializer#initialize(ConfigurableApplicationContext)
	 */
	protected void customizeContext(ServletContext sc, ConfigurableWebApplicationContext wac) {
		// 将servlet上下文中配置的初始化上下文实例对象加到initializerClasses中
		List<Class<ApplicationContextInitializer<ConfigurableApplicationContext>>> initializerClasses =
				determineContextInitializerClasses(sc);

		for (Class<ApplicationContextInitializer<ConfigurableApplicationContext>> initializerClass : initializerClasses) {
			// 通过泛型类型解析器来解析类型参数，得到指定类型的上下文类初始化器
			Class<?> initializerContextClass =
					GenericTypeResolver.resolveTypeArgument(initializerClass, ApplicationContextInitializer.class);
			if (initializerContextClass != null && !initializerContextClass.isInstance(wac)) {
				throw new ApplicationContextException(String.format(
						"Could not apply context initializer [%s] since its generic parameter [%s] " +
						"is not assignable from the type of application context used by this " +
						"context loader: [%s]", initializerClass.getName(), initializerContextClass.getName(),
						wac.getClass().getName()));
			}
			// 上下文类初始化器实例化后，添加到上下文初始化器的list中
			this.contextInitializers.add(BeanUtils.instantiateClass(initializerClass));
		}

		// 对该上下文初始化器内的实例进行排序
		AnnotationAwareOrderComparator.sort(this.contextInitializers);
		for (ApplicationContextInitializer<ConfigurableApplicationContext> initializer : this.contextInitializers) {
			initializer.initialize(wac);// 每个初始化器都进行初始化
		}
	}

	/**
	 * Return the {@link ApplicationContextInitializer} implementation classes to use
	 * if any have been specified by {@link #CONTEXT_INITIALIZER_CLASSES_PARAM}.
	 * 通过指定的CONTEXT_INITIALIZER_CLASSES_PARAM返回应用上下文初始化器实现类
	 * @param servletContext current servlet context
	 * @see #CONTEXT_INITIALIZER_CLASSES_PARAM
	 */
	protected List<Class<ApplicationContextInitializer<ConfigurableApplicationContext>>>
			determineContextInitializerClasses(ServletContext servletContext) {

		List<Class<ApplicationContextInitializer<ConfigurableApplicationContext>>> classes =
				new ArrayList<>();

		// 获取全局配置类名称，如果web.xml有配置的话
		String globalClassNames = servletContext.getInitParameter(GLOBAL_INITIALIZER_CLASSES_PARAM);
		if (globalClassNames != null) {
			for (String className : StringUtils.tokenizeToStringArray(globalClassNames, INIT_PARAM_DELIMITERS)) {
				// 如果全局配置类不为空，则将实例化后的类加到classes中
				classes.add(loadInitializerClass(className));
			}
		}

		// 获取上下文初始化类名称，如果web.xml有配置的话
		String localClassNames = servletContext.getInitParameter(CONTEXT_INITIALIZER_CLASSES_PARAM);
		if (localClassNames != null) {
			for (String className : StringUtils.tokenizeToStringArray(localClassNames, INIT_PARAM_DELIMITERS)) {
				// 如果上下文初始化类不为空，则将实例化后的类加到classes中
				classes.add(loadInitializerClass(className));
			}
		}

		// 上面两个，在web.xml好像不怎么配置，那这个list的size就是0了啊
		return classes;
	}

	@SuppressWarnings("unchecked")
	private Class<ApplicationContextInitializer<ConfigurableApplicationContext>> loadInitializerClass(String className) {
		try {
			// 实例化对象
			Class<?> clazz = ClassUtils.forName(className, ClassUtils.getDefaultClassLoader());
			if (!ApplicationContextInitializer.class.isAssignableFrom(clazz)) { // 判断ApplicationContextInitializer是否指定类的子类或实现类
				throw new ApplicationContextException(
						"Initializer class does not implement ApplicationContextInitializer interface: " + clazz);
			}
			return (Class<ApplicationContextInitializer<ConfigurableApplicationContext>>) clazz;
		}
		catch (ClassNotFoundException ex) {
			throw new ApplicationContextException("Failed to load context initializer class [" + className + "]", ex);
		}
	}

	/**
	 * Template method with default implementation (which may be overridden by a
	 * subclass), to load or obtain an ApplicationContext instance which will be
	 * used as the parent context of the root WebApplicationContext. If the
	 * return value from the method is null, no parent context is set.
	 * <p>The main reason to load a parent context here is to allow multiple root
	 * web application contexts to all be children of a shared EAR context, or
	 * alternately to also share the same parent context that is visible to
	 * EJBs. For pure web applications, there is usually no need to worry about
	 * having a parent context to the root web application context.
	 * <p>The default implementation simply returns {@code null}, as of 5.0.
	 * @param servletContext current servlet context
	 * @return the parent application context, or {@code null} if none
	 */
	@Nullable
	protected ApplicationContext loadParentContext(ServletContext servletContext) {
		return null;
	}

	/**
	 * Close Spring's web application context for the given servlet context.
	 * <p>If overriding {@link #loadParentContext(ServletContext)}, you may have
	 * to override this method as well.
	 * @param servletContext the ServletContext that the WebApplicationContext runs in
	 */
	public void closeWebApplicationContext(ServletContext servletContext) {
		servletContext.log("Closing Spring root WebApplicationContext");
		try {
			if (this.context instanceof ConfigurableWebApplicationContext) {
				((ConfigurableWebApplicationContext) this.context).close();
			}
		}
		finally {
			ClassLoader ccl = Thread.currentThread().getContextClassLoader();
			if (ccl == ContextLoader.class.getClassLoader()) {
				currentContext = null;
			}
			else if (ccl != null) {
				currentContextPerThread.remove(ccl);
			}
			servletContext.removeAttribute(WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE);
		}
	}


	/**
	 * Obtain the Spring root web application context for the current thread
	 * (i.e. for the current thread's context ClassLoader, which needs to be
	 * the web application's ClassLoader).
	 * @return the current root web application context, or {@code null}
	 * if none found
	 * @see org.springframework.web.context.support.SpringBeanAutowiringSupport
	 */
	@Nullable
	public static WebApplicationContext getCurrentWebApplicationContext() {
		ClassLoader ccl = Thread.currentThread().getContextClassLoader();
		if (ccl != null) {
			WebApplicationContext ccpt = currentContextPerThread.get(ccl);
			if (ccpt != null) {
				return ccpt;
			}
		}
		return currentContext;
	}

}
