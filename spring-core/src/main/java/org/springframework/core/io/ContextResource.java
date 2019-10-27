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

package org.springframework.core.io;

/**
 * Extended interface for a resource that is loaded from an enclosing
 * 'context', e.g. from a {@link javax.servlet.ServletContext} but also
 * from plain classpath paths or relative file system paths (specified
 * without an explicit prefix, hence applying relative to the local
 * {@link ResourceLoader}'s context).
 *
 *  该资源是从封闭的上下文加载的资源扩展接口，例如ServletContext。该类资源
 *  从单纯的类路径或相对路径（没有明显前缀，相对于本地资源的向下问）
 *  （我的理解是，出现这个接口的原因是由于其他资源都是可以从本地找到或者是通过
 *  网络等方式获取的。而上下文资源是从应用里面获取的。serveltContext存放的是应用
 *  在启动时获取的参数，路径等数据，而从serveltContext获取的这些数据资源就是上下文资源）
 * @author Juergen Hoeller
 * @since 2.5
 * @see org.springframework.web.context.support.ServletContextResource
 */
public interface ContextResource extends Resource {

	/**
	 * Return the path within the enclosing 'context'.
	 * <p>This is typically path relative to a context-specific root directory,
	 * e.g. a ServletContext root or a PortletContext root.
	 *
	 * 	 在封闭的上下文中返回路径。这是典型的相对于特定上下文根目录的相对路径，
	 * 	 例如Servlet上下文根目录或Portlet上上下文根目录
	 */
	String getPathWithinContext();

}
