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
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.io.*;
import java.net.URI;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.FileSystem;
import java.nio.file.*;

/**
 * {@link Resource} implementation for {@code java.io.File} and
 * {@code java.nio.file.Path} handles with a file system target.
 * Supports resolution as a {@code File} and also as a {@code URL}.
 * Implements the extended {@link WritableResource} interface.
 * 该Resource是针对File和 file.Path类资源的实现类，以处理文件系统资源为目标
 * 	支持将资源解析为File和URL。该类实现了WritableResource接口。（这个接口是spring5.0才有的）
 * <p>Note: As of Spring Framework 5.0, this {@link Resource} implementation uses
 * NIO.2 API for read/write interactions. As of 5.1, it may be constructed with a
 * {@link java.nio.file.Path} handle in which case it will perform all file system
 * interactions via NIO.2, only resorting to {@link File} on {@link #getFile()}.
 * 注意，spring5.0的资源实现类使用了 nio api作为读写交互。在5.1中，这类资源可能
 *   通过Path引用去构建，这种情况下它将通过nio2进行所有的文件交互，只有通过getFile获取文件。
 *   （我的理解，不是很理解这段话。但是，该说明表明了这个类的实现方式和以前有很大不同，起码是
 *   通过nio api来实现资源的获取交互，具体的可以根据下面的方法实现来理解）
 * @author Juergen Hoeller
 * @since 28.12.2003
 * @see #FileSystemResource(File)
 * @see #FileSystemResource(Path)
 * @see java.io.File
 * @see java.nio.file.Files
 */
public class FileSystemResource extends AbstractResource implements WritableResource {

	private final String path;

	@Nullable
	private final File file;

	private final Path filePath;


	/**
	 * Create a new {@code FileSystemResource} from a file path.
	 * <p>Note: When building relative resources via {@link #createRelative},
	 * it makes a difference whether the specified resource base path here
	 * ends with a slash or not. In the case of "C:/dir1/", relative paths
	 * will be built underneath that root: e.g. relative path "dir2" ->
	 * "C:/dir1/dir2". In the case of "C:/dir1", relative paths will apply
	 * at the same directory level: relative path "dir2" -> "C:/dir2".
	 *  从文件路径创建了一个新的FileSystemResource实例。注意：当以相对路径的方式
	 * 	 构建的时候，需要注意这个特定路径是否以"/"结尾。例如：当前路径为“c:/dir1/”，
	 * 	 相对路径是“dir2”，则创建时会从“c:/dir1/dir2”获取资源。若不以“/”结尾，则
	 * 	 在“c:/dir2”路径下获取资源。（通过相对路径创建资源的时候这一条需注意，很有用）
	 * @param path a file path
	 * @see #FileSystemResource(Path)
	 */
	public FileSystemResource(String path) {
		Assert.notNull(path, "Path must not be null");
		this.path = StringUtils.cleanPath(path);
		this.file = new File(path);
		this.filePath = this.file.toPath();
	}

	/**
	 * Create a new {@code FileSystemResource} from a {@link File} handle.
	 * <p>Note: When building relative resources via {@link #createRelative},
	 * the relative path will apply <i>at the same directory level</i>:
	 * e.g. new File("C:/dir1"), relative path "dir2" -> "C:/dir2"!
	 * If you prefer to have relative paths built underneath the given root directory,
	 * use the {@link #FileSystemResource(String) constructor with a file path}
	 * to append a trailing slash to the root path: "C:/dir1/", which indicates
	 * this directory as root for all relative paths.
	 *
	 * 	通过File类实例引用创建一个FileSystemResource实例。注意，如果File创建时是new File（"C:/dir1"），
	 * 	则相对路径dir2的真实路径是“c:/dir2”。（同path的解释一样，这里不多说了）
	 * @param file a File handle
	 * @see #FileSystemResource(Path)
	 * @see #getFile()
	 */
	public FileSystemResource(File file) {
		Assert.notNull(file, "File must not be null");
		this.path = StringUtils.cleanPath(file.getPath());
		this.file = file;
		this.filePath = file.toPath();
	}

	/**
	 * Create a new {@code FileSystemResource} from a {@link Path} handle,
	 * performing all file system interactions via NIO.2 instead of {@link File}.
	 * <p>In contrast to {@link PathResource}, this variant strictly follows the
	 * general {@link FileSystemResource} conventions, in particular in terms of
	 * path cleaning and {@link #createRelative(String)} handling.
	 *
	 *  通过Path实例创建一个新的FileSystemResource类，它是用过nio2而不是file来实现所有的文件系统交互。
	 * 	 与PathResource类相反，这个变体严格遵循常规FileSystemResource的约定，尤其是在路径整理和相对路径处理
	 * 	 这方面。
	 * @param filePath a Path handle to a file
	 * @since 5.1
	 * @see #FileSystemResource(File)
	 */
	public FileSystemResource(Path filePath) {
		Assert.notNull(filePath, "Path must not be null");
		this.path = StringUtils.cleanPath(filePath.toString());
		this.file = null;
		this.filePath = filePath;
	}

	/**
	 * Create a new {@code FileSystemResource} from a {@link FileSystem} handle,
	 * locating the specified path.
	 * <p>This is an alternative to {@link #FileSystemResource(String)},
	 * performing all file system interactions via NIO.2 instead of {@link File}.
	 * @param fileSystem the FileSystem to locate the path within
	 * @param path a file path
	 * @since 5.1.1
	 * @see #FileSystemResource(File)
	 *
	 * 这个方法是FileSystemResource(String)方法的替换（不多说）
	 */
	public FileSystemResource(FileSystem fileSystem, String path) {
		Assert.notNull(fileSystem, "FileSystem must not be null");
		Assert.notNull(path, "Path must not be null");
		this.path = StringUtils.cleanPath(path);
		this.file = null;
		this.filePath = fileSystem.getPath(this.path).normalize();
	}


	/**
	 * Return the file path for this resource.
	 */
	public final String getPath() {
		return this.path;
	}

	/**
	 * This implementation returns whether the underlying file exists.
	 * @see java.io.File#exists()
	 */
	@Override
	public boolean exists() {
		return (this.file != null ? this.file.exists() : Files.exists(this.filePath));
	}

	/**
	 * This implementation checks whether the underlying file is marked as readable
	 * (and corresponds to an actual file with content, not to a directory).
	 * 	 该实现检查该基础文件是否被标记为可读
	 * @see java.io.File#canRead()
	 * @see java.io.File#isDirectory()
	 */
	@Override
	public boolean isReadable() {
		return (this.file != null ? this.file.canRead() && !this.file.isDirectory() :
				Files.isReadable(this.filePath) && !Files.isDirectory(this.filePath));
	}

	/**
	 * This implementation opens a NIO file stream for the underlying file.
	 * 	该实现对于当前文件打开了一个nio文件流（spring 5.0的实现）
	 * @see java.io.FileInputStream
	 */
	@Override
	public InputStream getInputStream() throws IOException {
		try {
			return Files.newInputStream(this.filePath);
		}
		catch (NoSuchFileException ex) {
			throw new FileNotFoundException(ex.getMessage());
		}
	}

	/**
	 * This implementation checks whether the underlying file is marked as writable
	 * (and corresponds to an actual file with content, not to a directory).
	 * @see java.io.File#canWrite()
	 * @see java.io.File#isDirectory()
	 */
	@Override
	public boolean isWritable() {
		return (this.file != null ? this.file.canWrite() && !this.file.isDirectory() :
				Files.isWritable(this.filePath) && !Files.isDirectory(this.filePath));
	}

	/**
	 * This implementation opens a FileOutputStream for the underlying file.
	 * @see java.io.FileOutputStream
	 */
	@Override
	public OutputStream getOutputStream() throws IOException {
		return Files.newOutputStream(this.filePath);
	}

	/**
	 * This implementation returns a URL for the underlying file.
	 * @see java.io.File#toURI()
	 */
	@Override
	public URL getURL() throws IOException {
		return (this.file != null ? this.file.toURI().toURL() : this.filePath.toUri().toURL());
	}

	/**
	 * This implementation returns a URI for the underlying file.
	 * @see java.io.File#toURI()
	 */
	@Override
	public URI getURI() throws IOException {
		return (this.file != null ? this.file.toURI() : this.filePath.toUri());
	}

	/**
	 * This implementation always indicates a file.
	 */
	@Override
	public boolean isFile() {
		return true;
	}

	/**
	 * This implementation returns the underlying File reference.
	 */
	@Override
	public File getFile() {
		return (this.file != null ? this.file : this.filePath.toFile());
	}

	/**
	 * This implementation opens a FileChannel for the underlying file.
	 * @see java.nio.channels.FileChannel
	 */
	@Override
	public ReadableByteChannel readableChannel() throws IOException {
		try {
			return FileChannel.open(this.filePath, StandardOpenOption.READ);
		}
		catch (NoSuchFileException ex) {
			throw new FileNotFoundException(ex.getMessage());
		}
	}

	/**
	 * This implementation opens a FileChannel for the underlying file.
	 * @see java.nio.channels.FileChannel
	 */
	@Override
	public WritableByteChannel writableChannel() throws IOException {
		return FileChannel.open(this.filePath, StandardOpenOption.WRITE);
	}

	/**
	 * This implementation returns the underlying File/Path length.
	 */
	@Override
	public long contentLength() throws IOException {
		if (this.file != null) {
			// 该方法是调用的c语言编写的本地方法，在类Win32FileSystem中，获取文件长度
			long length = this.file.length();
			if (length == 0L && !this.file.exists()) {
				throw new FileNotFoundException(getDescription() +
						" cannot be resolved in the file system for checking its content length");
			}
			return length;
		}
		else {
			try {
				return Files.size(this.filePath);
			}
			catch (NoSuchFileException ex) {
				throw new FileNotFoundException(ex.getMessage());
			}
		}
	}

	/**
	 * This implementation returns the underlying File/Path last-modified time.
	 */
	@Override
	public long lastModified() throws IOException {
		if (this.file != null) {
			return super.lastModified();
		}
		else {
			try {
				return Files.getLastModifiedTime(this.filePath).toMillis();
			}
			catch (NoSuchFileException ex) {
				throw new FileNotFoundException(ex.getMessage());
			}
		}
	}

	/**
	 * This implementation creates a FileSystemResource, applying the given path
	 * relative to the path of the underlying file of this resource descriptor.
	 * @see org.springframework.util.StringUtils#applyRelativePath(String, String)
	 */
	@Override
	public Resource createRelative(String relativePath) {
		String pathToUse = StringUtils.applyRelativePath(this.path, relativePath);
		return (this.file != null ? new FileSystemResource(pathToUse) :
				new FileSystemResource(this.filePath.getFileSystem(), pathToUse));
	}

	/**
	 * This implementation returns the name of the file.
	 * @see java.io.File#getName()
	 */
	@Override
	public String getFilename() {
		return (this.file != null ? this.file.getName() : this.filePath.getFileName().toString());
	}

	/**
	 * This implementation returns a description that includes the absolute
	 * path of the file.
	 * @see java.io.File#getAbsolutePath()
	 */
	@Override
	public String getDescription() {
		return "file [" + (this.file != null ? this.file.getAbsolutePath() : this.filePath.toAbsolutePath()) + "]";
	}


	/**
	 * This implementation compares the underlying File references.
	 */
	@Override
	public boolean equals(Object other) {
		return (this == other || (other instanceof FileSystemResource &&
				this.path.equals(((FileSystemResource) other).path)));
	}

	/**
	 * This implementation returns the hash code of the underlying File reference.
	 */
	@Override
	public int hashCode() {
		return this.path.hashCode();
	}

}
