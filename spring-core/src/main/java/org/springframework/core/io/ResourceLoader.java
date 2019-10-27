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

package org.springframework.core.io;

import org.springframework.lang.Nullable;
import org.springframework.util.ResourceUtils;

/**
 * Strategy interface for loading resources (e.. class path or file system
 * resources). An {@link org.springframework.context.ApplicationContext}
 * is required to provide this functionality, plus extended
 * {@link org.springframework.core.io.support.ResourcePatternResolver} support.
 *  加载资源的策略接口（如类路径或者文件系统资源），ApplicationContext这个类就需要
 *  提供这种加载资源的策略。并且还需要ResourcePatternResolver类的支持
 *
 * <p>{@link DefaultResourceLoader} is a standalone implementation that is
 * usable outside an ApplicationContext, also used by {@link ResourceEditor}.
 *
 * <p>Bean properties of type Resource and Resource array can be populated
 * from Strings when running in an ApplicationContext, using the particular
 * context's resource loading strategy.
 *
 * 当应用上下文在运行时，使用特定上下文资源加载策略，
 * Resource和Resource数组类型的bean属性能够通过字符串来填充
 *   （其实也就是说能够通过字符串来加载文件，例如
 *  String[] locations = {"bean1.xml", "bean2.xml", "bean3.xml"};
 *   ApplicationContext ctx = new FileSystemXmlApplicationContext(locations ); //加载单个配置文件）
 *
 * @author Juergen Hoeller
 * @since 10.03.2004
 * @see Resource
 * @see org.springframework.core.io.support.ResourcePatternResolver
 * @see org.springframework.context.ApplicationContext
 * @see org.springframework.context.ResourceLoaderAware
 */
public interface ResourceLoader {

	/** Pseudo URL prefix for loading from the class path: "classpath:". */
	/*用于从类路径加载的伪URL前缀"classpath:"（对于web应用可以看到，当项目编译后可以看到有个.classpath文件
	这个文件里面配置的路径是<classpathentry kind="output" path="webapp/WEB-INF/classes"/>
	因此可以推断出获取的资源都是在这个路径下获取的）*/
	String CLASSPATH_URL_PREFIX = ResourceUtils.CLASSPATH_URL_PREFIX;


	/**
	 * Return a Resource handle for the specified resource location.
	 * <p>The handle should always be a reusable resource descriptor,
	 * allowing for multiple {@link Resource#getInputStream()} calls.
	 * 从一个特定的资源位置返回一个资源句柄（就是获取一个Resource）
	 * 	这个句柄应该总是能够重新使用的资源，允许多次调用getInputStream方法
	 * <p><ul>
	 * <li>Must support fully qualified URLs, e.g. "file:C:/test.dat".
	 * <li>Must support classpath pseudo-URLs, e.g. "classpath:test.dat".
	 * <li>Should support relative file paths, e.g. "WEB-INF/test.dat".
	 * (This will be implementation-specific, typically provided by an
	 * ApplicationContext implementation.)
	 * 该方法必须充分支持限定的urls，如"file:C:/test.dat".
	 * 该方法必须充分支持类伪路径，如"classpath:test.dat".
	 * 该方法必须充分支持相对路径，如"WEB-INF/test.dat".
	 * </ul>
	 * <p>Note that a Resource handle does not imply an existing resource;
	 * you need to invoke {@link Resource#exists} to check for existence.
	 *  注意该资源句柄并不意味着一个存在的资源，还需要调用exists方法检查其存在性
	 * @param location the resource location
	 * @return a corresponding Resource handle (never {@code null})
	 * @see #CLASSPATH_URL_PREFIX
	 * @see Resource#exists()
	 * @see Resource#getInputStream()
	 */
	Resource getResource(String location);

	/**
	 * Expose the ClassLoader used by this ResourceLoader.
	 * 暴露被这个资源加载器使用过的类加载器
	 * <p>Clients which need to access the ClassLoader directly can do so
	 * in a uniform manner with the ResourceLoader, rather than relying
	 * on the thread context ClassLoader.
	 * 需要去访问类加载器的客户端可以用ResourceLoader直接以统一的形式使用，
	 * 而不是依赖线程上下文类加载器
	 * （就是叫我们不要随便用线程上下文类加载器，而是用它提供的类加载器）
	 * @return the ClassLoader
	 * (only {@code null} if even the system ClassLoader isn't accessible)
	 * @see org.springframework.util.ClassUtils#getDefaultClassLoader()
	 * @see org.springframework.util.ClassUtils#forName(String, ClassLoader)
	 */
	@Nullable
	ClassLoader getClassLoader();

}
