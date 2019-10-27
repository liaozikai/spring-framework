/*
 * Copyright 2002-2017 the original author or authors.
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

import java.io.IOException;
import java.io.InputStream;

/**
 * Simple interface for objects that are sources for an {@link InputStream}.
 *
 *	输入流资源的对象而言，这是一个简单的接口
 *
 * <p>This is the base interface for Spring's more extensive {@link Resource} interface.
 *
 *	对于spring更多可扩展的资源接口而言，这是更加基本的接口
 *
 * <p>For single-use streams, {@link InputStreamResource} can be used for any
 * given {@code InputStream}. Spring's {@link ByteArrayResource} or any
 * file-based {@code Resource} implementation can be used as a concrete
 * instance, allowing one to read the underlying content stream multiple times.
 * This makes this interface useful as an abstract content source for mail
 * attachments, for example.
 *
 * 对于单次使用的流而言，InputStreamResource 能够被用于任何给定的输入流（也就是任何流都能够被抽象封装成 resource，多态的应用）。
 * Spirng 的 ByteArrayResource或者基于文件的Resource类 的实现都能够用作具体的实例，允许被多次读取下层内容流。
 * 例如，这使得接口对于作为邮件附件的抽象内容源是十分有用的。（本人理解，举了一个邮件附件例子，说明允许多次读取下层内容流。）
 *
 * @author Juergen Hoeller
 * @since 20.01.2004
 * @see java.io.InputStream
 * @see Resource
 * @see InputStreamResource
 * @see ByteArrayResource
 */
public interface InputStreamSource {

	/**
	 * Return an {@link InputStream} for the content of an underlying resource.
	 * <p>It is expected that each call creates a <i>fresh</i> stream.
	 * <p>This requirement is particularly important when you consider an API such
	 * as JavaMail, which needs to be able to read the stream multiple times when
	 * creating mail attachments. For such a use case, it is <i>required</i>
	 * that each {@code getInputStream()} call returns a fresh stream.
	 * @return the input stream for the underlying resource (must not be {@code null})
	 * @throws java.io.FileNotFoundException if the underlying resource doesn't exist
	 * @throws IOException if the content stream could not be opened
	 *
	 * 对于下层资源内容而言返回InputSteam。该方法每次调用都应该创建一个新的流。
	 * 当你考虑到调用一些API例如JavaMail的时候这个要求显得十分重要，
	 * 当创建邮件附件的时候它需要能够多次地去读取这些流。
	 * 对于这样一个案例而言，他需要每次调用都返回一个新的流
	 * （我查看了其子类实现中FileSystemResource和UrlResource的getInputStream方法，
	 * 的确几乎都是返回一个新的流。其中UrlResource的getInputSteam方法是调用URLConnection的getInputStream方法，
	 * 而该方法又是由URLConnection的实现类去实现的，一定要小心注意，
	 * 因为URLConnection的该方法是抛出异常，如果没查看它的继承树，还以为就是该方法就是抛异常。
	 * 而InputStreamResource类的getInputStream方法则是不允许调用多次，会返回异常！）
	 */
	InputStream getInputStream() throws IOException;

}
