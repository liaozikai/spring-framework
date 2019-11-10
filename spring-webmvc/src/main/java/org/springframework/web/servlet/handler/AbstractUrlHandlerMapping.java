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

package org.springframework.web.servlet.handler;

import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.web.servlet.HandlerExecutionChain;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.*;

/**
 * Abstract base class for URL-mapped {@link org.springframework.web.servlet.HandlerMapping}
 * implementations. Provides infrastructure for mapping handlers to URLs and configurable
 * URL lookup. For information on the latter, see "alwaysUseFullPath" property.
 *
 * <p>Supports direct matches, e.g. a registered "/test" matches "/test", and
 * various Ant-style pattern matches, e.g. a registered "/t*" pattern matches
 * both "/test" and "/team", "/test/*" matches all paths in the "/test" directory,
 * "/test/**" matches all paths below "/test". For details, see the
 * {@link org.springframework.util.AntPathMatcher AntPathMatcher} javadoc.
 *
 * <p>Will search all path patterns to find the most exact match for the
 * current request path. The most exact match is defined as the longest
 * path pattern that matches the current request path.
 *
 * @author Juergen Hoeller
 * @author Arjen Poutsma
 * @since 16.04.2003
 */
public abstract class AbstractUrlHandlerMapping extends AbstractHandlerMapping implements MatchableHandlerMapping {

	@Nullable
	private Object rootHandler;

	private boolean useTrailingSlashMatch = false;

	private boolean lazyInitHandlers = false;

	private final Map<String, Object> handlerMap = new LinkedHashMap<>();


	/**
	 * Set the root handler for this handler mapping, that is,
	 * the handler to be registered for the root path ("/").
	 * <p>Default is {@code null}, indicating no root handler.
	 * 设置此处理程序映射的根处理程序，即为根路径（“ /”）注册的处理程序。
	 * 默认是null，表明没有根控制器
	 */
	public void setRootHandler(@Nullable Object rootHandler) {
		this.rootHandler = rootHandler;
	}

	/**
	 * Return the root handler for this handler mapping (registered for "/"),
	 * or {@code null} if none.
	 */
	@Nullable
	public Object getRootHandler() {
		return this.rootHandler;
	}

	/**
	 * Whether to match to URLs irrespective of the presence of a trailing slash.
	 * If enabled a URL pattern such as "/users" also matches to "/users/".
	 * <p>The default value is {@code false}.
	 * 是否与URL匹配，无论是否存在斜杠。 如果启用，URL模式（例如“ / users”）
	 * 也将匹配到“ / users /”。默认值为{@code false}。
	 */
	public void setUseTrailingSlashMatch(boolean useTrailingSlashMatch) {
		this.useTrailingSlashMatch = useTrailingSlashMatch;
	}

	/**
	 * Whether to match to URLs irrespective of the presence of a trailing slash.
	 */
	public boolean useTrailingSlashMatch() {
		return this.useTrailingSlashMatch;
	}

	/**
	 * Set whether to lazily initialize handlers. Only applicable to
	 * singleton handlers, as prototypes are always lazily initialized.
	 * Default is "false", as eager initialization allows for more efficiency
	 * through referencing the controller objects directly.
	 * <p>If you want to allow your controllers to be lazily initialized,
	 * make them "lazy-init" and set this flag to true. Just making them
	 * "lazy-init" will not work, as they are initialized through the
	 * references from the handler mapping in this case.
	 * 设置是否延迟初始化控制器
	 */
	public void setLazyInitHandlers(boolean lazyInitHandlers) {
		this.lazyInitHandlers = lazyInitHandlers;
	}

	/**
	 * Look up a handler for the URL path of the given request.
	 * 对于给定请求的URL路径查找控制器
	 * @param request current HTTP request
	 * @return the handler instance, or {@code null} if none found
	 */
	@Override
	@Nullable
	protected Object getHandlerInternal(HttpServletRequest request) throws Exception {
		// 获取给定请求的查找路径
		String lookupPath = getUrlPathHelper().getLookupPathForRequest(request);
		// 通过请求路径和request获取控制器
		Object handler = lookupHandler(lookupPath, request);
		if (handler == null) {// 若控制器为null
			// We need to care for the default handler directly, since we need to
			// expose the PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE for it as well.
			Object rawHandler = null;
			if ("/".equals(lookupPath)) {// 若查找路径为“/”，则将根控制器赋值为变量
				rawHandler = getRootHandler();
			}
			if (rawHandler == null) {// 若变量还是为null，则获取默认控制器
				rawHandler = getDefaultHandler();
			}
			if (rawHandler != null) {
				// Bean name or resolved handler? 控制器是名称还是实体
				if (rawHandler instanceof String) {// 若是名称，通过应用上下文获取控制器
					String handlerName = (String) rawHandler;
					rawHandler = obtainApplicationContext().getBean(handlerName);
				}
				validateHandler(rawHandler, request);// 空校验
				handler = buildPathExposingHandler(rawHandler, lookupPath, lookupPath, null);// 获取控制器执行链对象
			}
		}
		return handler;
	}

	/**
	 * Look up a handler instance for the given URL path.
	 * <p>Supports direct matches, e.g. a registered "/test" matches "/test",
	 * and various Ant-style pattern matches, e.g. a registered "/t*" matches
	 * both "/test" and "/team". For details, see the AntPathMatcher class.
	 * <p>Looks for the most exact pattern, where most exact is defined as
	 * the longest path pattern.
	 * 查找给定URL路径的处理程序实例。
	 * @param urlPath the URL the bean is mapped to
	 * @param request current HTTP request (to expose the path within the mapping to)
	 * @return the associated handler instance, or {@code null} if not found
	 * @see #exposePathWithinMapping
	 * @see org.springframework.util.AntPathMatcher
	 */
	@Nullable
	protected Object lookupHandler(String urlPath, HttpServletRequest request) throws Exception {
		// Direct match? 直接匹配？
		// 获取给定url路径的控制器
		Object handler = this.handlerMap.get(urlPath);
		if (handler != null) {// 该控制器不为null
			// Bean name or resolved handler? 判断该控制器只是名称还是解析后的控制器
			if (handler instanceof String) {// 如果该控制器是名称
				String handlerName = (String) handler;
				// 从应用上下文中获取handlerName对应的控制器对象
				handler = obtainApplicationContext().getBean(handlerName);
			}
			validateHandler(handler, request);// 进行校验，默认为空
			// 返回一个控制器执行链对象
			return buildPathExposingHandler(handler, urlPath, urlPath, null);
		}

		// Pattern match? 模式匹配？
		List<String> matchingPatterns = new ArrayList<>();
		for (String registeredPattern : this.handlerMap.keySet()) {// 遍历控制器map中所有的key值
			if (getPathMatcher().match(registeredPattern, urlPath)) {// 若是key值等于urlPath请求路径
				matchingPatterns.add(registeredPattern);// 则匹配模式list中添加该key值
			}
			else if (useTrailingSlashMatch()) {// 如果启用尾部斜杠匹配
				if (!registeredPattern.endsWith("/") && getPathMatcher().match(registeredPattern + "/", urlPath)) {
					matchingPatterns.add(registeredPattern + "/");// 则匹配模式list中添加该key值
				}
			}
		}

		String bestMatch = null;
		// 获取urlPath对应的模式匹配器
		Comparator<String> patternComparator = getPathMatcher().getPatternComparator(urlPath);
		if (!matchingPatterns.isEmpty()) {// 匹配模式中的路径不为空
			matchingPatterns.sort(patternComparator);// 对这些路径进行排序
			if (logger.isTraceEnabled() && matchingPatterns.size() > 1) {
				logger.trace("Matching patterns " + matchingPatterns);
			}
			bestMatch = matchingPatterns.get(0);// 获取最匹配的路径
		}
		if (bestMatch != null) {
			handler = this.handlerMap.get(bestMatch);// 通过最匹配路径获得控制器
			if (handler == null) {// 控制器为空
				if (bestMatch.endsWith("/")) {// 则获取以斜杠结尾的路径对应的控制器
					handler = this.handlerMap.get(bestMatch.substring(0, bestMatch.length() - 1));
				}
				if (handler == null) {// 还是为空则抛出异常
					throw new IllegalStateException(
							"Could not find handler for best pattern match [" + bestMatch + "]");
				}
			}
			// Bean name or resolved handler? 该控制器是beanName还是已解析的控制器
			if (handler instanceof String) {// 若还是名称，则通过应用上下文获取对应bean对象
				String handlerName = (String) handler;
				handler = obtainApplicationContext().getBean(handlerName);
			}
			validateHandler(handler, request);// 再进行校验，默认为空校验
			// 获取最匹配路径和全路径之间的差异部分，具体可查看AntPathMatcher的注释
			String pathWithinMapping = getPathMatcher().extractPathWithinPattern(bestMatch, urlPath);

			// There might be multiple 'best patterns', let's make sure we have the correct URI template variables
			// for all of them   这里可能有多个最好的匹配模式，我们确定所有适合他们的uri模版变量
			Map<String, String> uriTemplateVariables = new LinkedHashMap<>();
			for (String matchingPattern : matchingPatterns) {// 遍历每个匹配模式
				if (patternComparator.compare(bestMatch, matchingPattern) == 0) {// 若是匹配模式与最匹配路径相符
					Map<String, String> vars = getPathMatcher().extractUriTemplateVariables(matchingPattern, urlPath);
					Map<String, String> decodedVars = getUrlPathHelper().decodePathVariables(request, vars);
					uriTemplateVariables.putAll(decodedVars);// 将匹配模式和url路径的映射放到uriTemplateVariables
				}
			}
			if (logger.isTraceEnabled() && uriTemplateVariables.size() > 0) {
				logger.trace("URI variables " + uriTemplateVariables);
			}

			// 返回一个控制器执行链对象
			return buildPathExposingHandler(handler, bestMatch, pathWithinMapping, uriTemplateVariables);
		}

		// No handler found...
		return null;
	}

	/**
	 * Validate the given handler against the current request.
	 * <p>The default implementation is empty. Can be overridden in subclasses,
	 * for example to enforce specific preconditions expressed in URL mappings.
	 * @param handler the handler object to validate
	 * @param request current HTTP request
	 * @throws Exception if validation failed
	 */
	protected void validateHandler(Object handler, HttpServletRequest request) throws Exception {
	}

	/**
	 * Build a handler object for the given raw handler, exposing the actual
	 * handler, the {@link #PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE}, as well as
	 * the {@link #URI_TEMPLATE_VARIABLES_ATTRIBUTE} before executing the handler.
	 * <p>The default implementation builds a {@link HandlerExecutionChain}
	 * with a special interceptor that exposes the path attribute and uri template variables
	 * 对于给定的原控制器创建一个控制器对象，并且在执行控制器之前，公开真实的控制器PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE
	 * 和URI_TEMPLATE_VARIABLES_ATTRIBUTE。 该方法默认实现是用特殊拦截器，公开路径属性和uri模版变量，
	 * 构建一个HandlerExecutionChain，
	 * @param rawHandler the raw handler to expose 公开的原控制器
	 * @param pathWithinMapping the path to expose before executing the handler 执行拦截器之前公开的路径
	 * @param uriTemplateVariables the URI template variables, can be {@code null} if no variables found uri模版变量，如果找不到变量可为null
	 * @return the final handler object  返回最终的控制器对象
	 */
	protected Object buildPathExposingHandler(Object rawHandler, String bestMatchingPattern,
			String pathWithinMapping, @Nullable Map<String, String> uriTemplateVariables) {

		// 通过原控制器对象创建了一个控制器执行链对象，然后添加了两个拦截器后返回
		HandlerExecutionChain chain = new HandlerExecutionChain(rawHandler);
		chain.addInterceptor(new PathExposingHandlerInterceptor(bestMatchingPattern, pathWithinMapping));
		if (!CollectionUtils.isEmpty(uriTemplateVariables)) {
			chain.addInterceptor(new UriTemplateVariablesHandlerInterceptor(uriTemplateVariables));
		}
		return chain;
	}

	/**
	 * Expose the path within the current mapping as request attribute.
	 * @param pathWithinMapping the path within the current mapping
	 * @param request the request to expose the path to
	 * @see #PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE
	 */
	protected void exposePathWithinMapping(String bestMatchingPattern, String pathWithinMapping,
			HttpServletRequest request) {

		request.setAttribute(BEST_MATCHING_PATTERN_ATTRIBUTE, bestMatchingPattern);
		request.setAttribute(PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE, pathWithinMapping);
	}

	/**
	 * Expose the URI templates variables as request attribute.
	 * @param uriTemplateVariables the URI template variables
	 * @param request the request to expose the path to
	 * @see #PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE
	 */
	protected void exposeUriTemplateVariables(Map<String, String> uriTemplateVariables, HttpServletRequest request) {
		request.setAttribute(URI_TEMPLATE_VARIABLES_ATTRIBUTE, uriTemplateVariables);
	}

	@Override
	@Nullable
	public RequestMatchResult match(HttpServletRequest request, String pattern) {
		// 通过request获取查找路径
		String lookupPath = getUrlPathHelper().getLookupPathForRequest(request);
		if (getPathMatcher().match(pattern, lookupPath)) {// 匹配模式和查找路径想符合
			return new RequestMatchResult(pattern, lookupPath, getPathMatcher());// 返回一个匹配结果
		}
		else if (useTrailingSlashMatch()) {
			if (!pattern.endsWith("/") && getPathMatcher().match(pattern + "/", lookupPath)) {// 若是启动尾部斜杠匹配，也是构造一个匹配结果
				return new RequestMatchResult(pattern + "/", lookupPath, getPathMatcher());
			}
		}
		return null;
	}

	/**
	 * Register the specified handler for the given URL paths.
	 * @param urlPaths the URLs that the bean should be mapped to
	 * @param beanName the name of the handler bean
	 * @throws BeansException if the handler couldn't be registered
	 * @throws IllegalStateException if there is a conflicting handler registered
	 */
	protected void registerHandler(String[] urlPaths, String beanName) throws BeansException, IllegalStateException {
		Assert.notNull(urlPaths, "URL path array must not be null");
		for (String urlPath : urlPaths) {
			registerHandler(urlPath, beanName);
		}
	}

	/**
	 * Register the specified handler for the given URL path.
	 * 对于给定的url路径注册指定控制器，也就是将url与控制器的映射方法handlerMap中
	 * @param urlPath the URL the bean should be mapped to
	 * @param handler the handler instance or handler bean name String
	 * (a bean name will automatically be resolved into the corresponding handler bean)
	 * @throws BeansException if the handler couldn't be registered
	 * @throws IllegalStateException if there is a conflicting handler registered
	 */
	protected void registerHandler(String urlPath, Object handler) throws BeansException, IllegalStateException {
		Assert.notNull(urlPath, "URL path must not be null");
		Assert.notNull(handler, "Handler object must not be null");
		Object resolvedHandler = handler;

		// Eagerly resolve handler if referencing singleton via name.
		if (!this.lazyInitHandlers && handler instanceof String) {
			String handlerName = (String) handler;
			ApplicationContext applicationContext = obtainApplicationContext();
			if (applicationContext.isSingleton(handlerName)) {
				resolvedHandler = applicationContext.getBean(handlerName);
			}
		}

		Object mappedHandler = this.handlerMap.get(urlPath);
		if (mappedHandler != null) {
			if (mappedHandler != resolvedHandler) {//  从应用上下文获取的和从handlerMap中获取的必须一致
				throw new IllegalStateException(
						"Cannot map " + getHandlerDescription(handler) + " to URL path [" + urlPath +
						"]: There is already " + getHandlerDescription(mappedHandler) + " mapped.");
			}
		}
		else {
			if (urlPath.equals("/")) {
				if (logger.isTraceEnabled()) {
					logger.trace("Root mapping to " + getHandlerDescription(handler));
				}
				setRootHandler(resolvedHandler);// 如果urlPath为“/”，则设置为根路径控制器
			}
			else if (urlPath.equals("/*")) {
				if (logger.isTraceEnabled()) {
					logger.trace("Default mapping to " + getHandlerDescription(handler));
				}
				setDefaultHandler(resolvedHandler);// 如果urlPath为“/*”,则设置为默认控制器
			}
			else {
				// 否则的话，每个urlPath都对应一个解析控制器
				this.handlerMap.put(urlPath, resolvedHandler);
				if (logger.isTraceEnabled()) {
					logger.trace("Mapped [" + urlPath + "] onto " + getHandlerDescription(handler));
				}
			}
		}
	}

	private String getHandlerDescription(Object handler) {
		return (handler instanceof String ? "'" + handler + "'" : handler.toString());
	}


	/**
	 * Return the registered handlers as an unmodifiable Map, with the registered path
	 * as key and the handler object (or handler bean name in case of a lazy-init handler)
	 * as value.
	 * @see #getDefaultHandler()
	 */
	public final Map<String, Object> getHandlerMap() {
		return Collections.unmodifiableMap(this.handlerMap);
	}

	/**
	 * Indicates whether this handler mapping support type-level mappings. Default to {@code false}.
	 */
	protected boolean supportsTypeLevelMappings() {
		return false;
	}


	/**
	 * Special interceptor for exposing the
	 * {@link AbstractUrlHandlerMapping#PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE} attribute.
	 * @see AbstractUrlHandlerMapping#exposePathWithinMapping
	 * 特殊拦截器
	 */
	private class PathExposingHandlerInterceptor extends HandlerInterceptorAdapter {

		private final String bestMatchingPattern;

		private final String pathWithinMapping;

		public PathExposingHandlerInterceptor(String bestMatchingPattern, String pathWithinMapping) {
			this.bestMatchingPattern = bestMatchingPattern;
			this.pathWithinMapping = pathWithinMapping;
		}

		@Override
		public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
			// 暴露 BEST_MATCHING_PATTERN_ATTRIBUTE、PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE  属性，就是设置属性而已
			exposePathWithinMapping(this.bestMatchingPattern, this.pathWithinMapping, request);
			request.setAttribute(BEST_MATCHING_HANDLER_ATTRIBUTE, handler);
			request.setAttribute(INTROSPECT_TYPE_LEVEL_MAPPING, supportsTypeLevelMappings());
			return true;
		}

	}

	/**
	 * Special interceptor for exposing the
	 * {@link AbstractUrlHandlerMapping#URI_TEMPLATE_VARIABLES_ATTRIBUTE} attribute.
	 * @see AbstractUrlHandlerMapping#exposePathWithinMapping
	 */
	private class UriTemplateVariablesHandlerInterceptor extends HandlerInterceptorAdapter {

		private final Map<String, String> uriTemplateVariables;

		public UriTemplateVariablesHandlerInterceptor(Map<String, String> uriTemplateVariables) {
			this.uriTemplateVariables = uriTemplateVariables;
		}

		@Override
		public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
			// 这里也是设置属性
			exposeUriTemplateVariables(this.uriTemplateVariables, request);
			return true;
		}
	}

}
