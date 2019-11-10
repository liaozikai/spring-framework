/*
 * Copyright 2002-2007 the original author or authors.
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

package org.springframework.core.io.support;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;

/**
 * Strategy interface for resolving a location pattern (for example,
 * an Ant-style path pattern) into Resource objects.
 * 解析位置模式的策略接口到资源对象。
 * <p>This is an extension to the {@link org.springframework.core.io.ResourceLoader}
 * interface. A passed-in ResourceLoader (for example, an
 * {@link org.springframework.context.ApplicationContext} passed in via
 * {@link org.springframework.context.ResourceLoaderAware} when running in a context)
 * can be checked whether it implements this extended interface too.
 * 这是ResourceLoader接口的扩展。 可以检查传入的ResourceLoader（例如，在上下文中
 * 运行时通过ResourceLoaderAware传入的ApplicationContext）是否也实现了此扩展接口。
 *
 * <p>{@link PathMatchingResourcePatternResolver} is a standalone implementation
 * that is usable outside an ApplicationContext, also used by
 * {@link ResourceArrayPropertyEditor} for populating Resource array bean properties.
 * PathMatchingResourcePatternResolver是一个独立的实现，可以在ApplicationContext外部使用，
 * 同样能够通过ResourceArrayPropertyEditor填充资源bean属性数组。
 * <p>Can be used with any sort of location pattern (e.g. "/WEB-INF/*-context.xml"):
 * Input patterns have to match the strategy implementation. This interface just
 * specifies the conversion method rather than a specific pattern format.
 * 可以与任何类型的位置模式一起使用（例如“ /WEB-INF/*-context.xml”）：
 * 输入模式必须与策略实现相匹配。 该接口仅指定转换方法，而不是特定的模式格式。
 * <p>This interface also suggests a new resource prefix "classpath*:" for all
 * matching resources from the class path. Note that the resource location is
 * expected to be a path without placeholders in this case (e.g. "/beans.xml");
 * JAR files or classes directories can contain multiple files of the same name.
 * 此接口还为类路径中的所有匹配资源建议一个新的资源前缀“ classpath *：”。
 * 请注意，在这种情况下，资源位置应该是没有占位符的路径（例如“ /beans.xml”）；
 * JAR文件或类目录可以包含多个相同名称的文件。
 *
 * @author Juergen Hoeller
 * @since 1.0.2
 * @see org.springframework.core.io.Resource
 * @see org.springframework.core.io.ResourceLoader
 * @see org.springframework.context.ApplicationContext
 * @see org.springframework.context.ResourceLoaderAware
 */
public interface ResourcePatternResolver extends ResourceLoader {

	/**
	 * Pseudo URL prefix for all matching resources from the class path: "classpath*:"
	 * This differs from ResourceLoader's classpath URL prefix in that it
	 * retrieves all matching resources for a given name (e.g. "/beans.xml"),
	 * for example in the root of all deployed JAR files.
	 * 类路径中所有匹配资源的伪URL前缀：“ classpath *：”这与ResourceLoader的
	 * 类路径URL前缀不同，在于它检索给定名称（例如“ /beans.xml”）的所有匹配资源，
	 * 例如在根目录中 所有已部署的JAR文件。
	 * @see org.springframework.core.io.ResourceLoader#CLASSPATH_URL_PREFIX
	 */
	String CLASSPATH_ALL_URL_PREFIX = "classpath*:";

	/**
	 * Resolve the given location pattern into Resource objects.
	 * <p>Overlapping resource entries that point to the same physical
	 * resource should be avoided, as far as possible. The result should
	 * have set semantics.
	 * 将给定的位置模式解析为Resource对象，应尽可能避免指向相同物理资源
	 * 的资源条目重叠。 结果应具有设定的语义。
	 * @param locationPattern the location pattern to resolve
	 * @return the corresponding Resource objects
	 * @throws IOException in case of I/O errors
	 */
	Resource[] getResources(String locationPattern) throws IOException;

}
