/*
 * Copyright 2002-2015 the original author or authors.
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

package org.springframework.core.type;

import org.springframework.lang.Nullable;
import org.springframework.util.MultiValueMap;

import java.util.Map;

/**
 * Defines access to the annotations of a specific type ({@link AnnotationMetadata class}
 * or {@link MethodMetadata method}), in a form that does not necessarily require the
 * class-loading.
 * 定义对特定类型的注释AnnotationMetadata类或MethodMetadata方法的访问，访问形式不一定需要加载类。
 * @author Juergen Hoeller
 * @author Mark Fisher
 * @author Mark Pollack
 * @author Chris Beams
 * @author Phillip Webb
 * @author Sam Brannen
 * @since 4.0
 * @see AnnotationMetadata
 * @see MethodMetadata
 */
public interface AnnotatedTypeMetadata {

	/**
	 * Determine whether the underlying element has an annotation or meta-annotation
	 * of the given type defined.
	 * 确定基础元素是否具有定义的给定类型的注释或元注释。
	 * <p>If this method returns {@code true}, then
	 * {@link #getAnnotationAttributes} will return a non-null Map.
	 * 	如果该方法返回true，那么getAnnotationAttributes方法返回非空map
	 * @param annotationName the fully qualified class name of the annotation
	 * type to look for 要查找的注释类型的标准类名
	 * @return whether a matching annotation is defined
	 */
	boolean isAnnotated(String annotationName);

	/**
	 * Retrieve the attributes of the annotation of the given type, if any (i.e. if
	 * defined on the underlying element, as direct annotation or meta-annotation),
	 * also taking attribute overrides on composed annotations into account.
	 * 检索给定类型的注释的属性（如果有）（即，如果在基础元素上定义为直接注释或元注释），
	 * 也要考虑对组合注释的属性覆盖。
	 * @param annotationName the fully qualified class name of the annotation
	 * type to look for
	 * @return a Map of attributes, with the attribute name as key (e.g. "value")
	 * and the defined attribute value as Map value. This return value will be
	 * {@code null} if no matching annotation is defined.
	 * 属性map，属性名称为键（例如“ value”），定义的属性值为Map值。
	 * 如果未定义匹配的注释，则此返回值为{@code null}。
	 */
	@Nullable
	Map<String, Object> getAnnotationAttributes(String annotationName);

	/**
	 * Retrieve the attributes of the annotation of the given type, if any (i.e. if
	 * defined on the underlying element, as direct annotation or meta-annotation),
	 * also taking attribute overrides on composed annotations into account.
	 * @param annotationName the fully qualified class name of the annotation
	 * type to look for
	 * @param classValuesAsString whether to convert class references to String
	 * class names for exposure as values in the returned Map, instead of Class
	 * references which might potentially have to be loaded first
	 * 是否将类引用转换为String类名称以在返回的Map中作为值公开，而不是可能首先要加载的Class引用
	 * @return a Map of attributes, with the attribute name as key (e.g. "value")
	 * and the defined attribute value as Map value. This return value will be
	 * {@code null} if no matching annotation is defined.
	 */
	@Nullable
	Map<String, Object> getAnnotationAttributes(String annotationName, boolean classValuesAsString);

	/**
	 * Retrieve all attributes of all annotations of the given type, if any (i.e. if
	 * defined on the underlying element, as direct annotation or meta-annotation).
	 * Note that this variant does <i>not</i> take attribute overrides into account.
	 * @param annotationName the fully qualified class name of the annotation
	 * type to look for
	 * @return a MultiMap of attributes, with the attribute name as key (e.g. "value")
	 * and a list of the defined attribute values as Map value. This return value will
	 * be {@code null} if no matching annotation is defined.
	 * @see #getAllAnnotationAttributes(String, boolean)
	 */
	@Nullable
	MultiValueMap<String, Object> getAllAnnotationAttributes(String annotationName);

	/**
	 * Retrieve all attributes of all annotations of the given type, if any (i.e. if
	 * defined on the underlying element, as direct annotation or meta-annotation).
	 * Note that this variant does <i>not</i> take attribute overrides into account.
	 * @param annotationName the fully qualified class name of the annotation
	 * type to look for
	 * @param classValuesAsString  whether to convert class references to String
	 * @return a MultiMap of attributes, with the attribute name as key (e.g. "value")
	 * and a list of the defined attribute values as Map value. This return value will
	 * be {@code null} if no matching annotation is defined.
	 * @see #getAllAnnotationAttributes(String)
	 */
	@Nullable
	MultiValueMap<String, Object> getAllAnnotationAttributes(String annotationName, boolean classValuesAsString);

}
