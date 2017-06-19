/**
 * cormorant - Object Storage Server
 * Copyright © 2017 WebFolder OÜ (support@webfolder.io)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package io.webfolder.cormorant.test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.Files.exists;
import static java.nio.file.Files.isDirectory;
import static org.jclouds.blobstore.options.PutOptions.Builder.multipart;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.runners.MethodSorters.NAME_ASCENDING;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Scanner;

import org.jclouds.blobstore.domain.Blob;
import org.jclouds.http.HttpRequest;
import org.jclouds.http.HttpResponseException;
import org.jclouds.http.options.GetOptions;
import org.jclouds.io.Payload;
import org.jclouds.io.Payloads;
import org.jclouds.openstack.swift.v1.domain.Container;
import org.jclouds.openstack.swift.v1.domain.SwiftObject;
import org.jclouds.openstack.swift.v1.features.ObjectApi;
import org.jclouds.openstack.swift.v1.options.CopyOptions;
import org.jclouds.openstack.swift.v1.options.PutOptions;
import org.jclouds.openstack.swift.v1.reference.SwiftHeaders;
import org.junit.FixMethodOrder;
import org.junit.Test;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;

import io.webfolder.cormorant.api.exception.CormorantException;

@FixMethodOrder(NAME_ASCENDING)
public class TestContainer extends TestSwift {

    @Test
    public void t01CreateContainer() {
        containerApi.create("container1");
        assertTrue(exists(objectStore.resolve("container1")));
        assertTrue(isDirectory(objectStore.resolve("container1")));
    }

    @Test
    public void t02CreateContainerOverwrite() {
        containerApi.create("container1");
        assertTrue(exists(objectStore.resolve("container1")));
    }

    @Test
    public void t03ListContainer() {
        Iterator<Container> iter = containerApi.list().iterator();
        assertTrue(iter.hasNext());
        Container container = iter.next();
        assertEquals("container1", container.getName());
        assertEquals(0L, container.getBytesUsed().longValue());
        assertEquals(0L, container.getObjectCount().longValue());
    }

    @Test
    public void t04GetEmptyMetadata() {
        Container container = containerApi.get("container1");
        assertTrue(container.getMetadata().isEmpty());
    }

    @Test
    public void t05SetMetadata() {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("foo", "bar");
        metadata.put("bar", "foo");
        containerApi.updateMetadata("container1", metadata);
    }

    @Test
    public void t06GetMetadata() {
        Container container = containerApi.get("container1");
        Map<String, String> metadata = container.getMetadata();
        assertFalse(metadata.isEmpty());
        assertEquals("bar", metadata.get("foo"));
        assertEquals("foo", metadata.get("bar"));
    }

    @Test
    public void t07DeleteMetadata() {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("bar", "");
        containerApi.deleteMetadata("container1", metadata);
        metadata = containerApi.get("container1").getMetadata();
        assertEquals(1, metadata.size());
        assertEquals("bar", metadata.get("foo"));
        containerApi.deleteMetadata("container1", metadata);
        metadata = containerApi.get("container1").getMetadata();
        assertEquals(0, metadata.size());
    }

    @Test
    public void t08Upload() {
        ObjectApi objectApi = swiftApi.getObjectApi(region, "container1");
        Payload payload = Payloads.newStringPayload("hello, world!");
        PutOptions options = new PutOptions();
        Map<String, String> metadata = new HashMap<>();
        metadata.put("my-key", "my-value");
        options.metadata(metadata);
        Multimap<String, String> headers = ArrayListMultimap.create();
        headers.put("X-Detect-Content-Type", "true");
        options.headers(headers);
        objectApi.put("test-2/hi.txt", payload, options);
    }

    @Test
    public void t09Multipart() {
        String data = "foo bar xyz fooo test-2 fooo fi fo foo fu fu";
        Payload p = Payloads.newStringPayload(data);
        p.getContentMetadata().setContentType("text/plain");
        p.getContentMetadata().setContentLength((long) data.length());
        Blob blob = blobStore.blobBuilder("hello").payload(p).build();
        blobStore.putBlob("container1", blob, multipart());
        blobStore.getContext().close();
    }

    @Test
    public void t10DeleteContainer() {
        boolean delete = containerApi.deleteIfEmpty("container1");
        assertFalse(delete);
    }

    @Test
    public void t11UpdateObjectMetadata() {
        ObjectApi objectApi = swiftApi.getObjectApi(region, "container1");
        Map<String, String> metadata = new HashMap<>();
        metadata.put("foo", "bar");
        metadata.put("bar", "foo");
        objectApi.updateMetadata("test-2/hi.txt", metadata);
        metadata = objectApi.getWithoutBody("test-2/hi.txt").getMetadata();
        assertEquals("bar", metadata.get("foo"));
        assertEquals("foo", metadata.get("bar"));
        assertEquals("my-value", metadata.get("my-key"));
    }

    @Test
    public void t12CopyObjectWithPut() {
        ObjectApi objectApi = swiftApi.getObjectApi(region, "container1");
        CopyOptions options = new CopyOptions();
        objectApi.copy("newfolder/myobject.txt", "container1", "test-2/hi.txt", options);
        SwiftObject swiftObject = objectApi.getWithoutBody("newfolder/myobject.txt");
        Map<String, String> metadata = swiftObject.getMetadata();
        assertFalse(metadata.isEmpty());
        assertEquals("bar", metadata.get("foo"));
        assertEquals("foo", metadata.get("bar"));
        assertEquals("my-value", metadata.get("my-key"));
    }

    @Test
    public void t13DeleteObject() {
        ObjectApi objectApi = swiftApi.getObjectApi(region, "container1");
        try {
            objectApi.delete("test-2");
        } catch (HttpResponseException t) {
            assertEquals("Failed to delete object [test-2]. Directory must be empty.",
                                new String((byte[]) t.getResponse().getPayload().getRawContent(), UTF_8));
        }
        objectApi.delete("test-2/hi.txt");
        objectApi.delete("test-2");
        SwiftObject object = objectApi.getWithoutBody("test-2/hi.txt");
        assertNull(object);
        object = objectApi.getWithoutBody("test-2");
        assertNull(object);
    }

    @Test
    public void t14ChunkedObjectUpload() {
        ObjectApi objectApi = swiftApi.getObjectApi(region, "container1");
        Payload payload = Payloads.newPayload(new ByteArrayInputStream("foo1 foo2 foo3 foo4".getBytes()));
        payload.getContentMetadata().setContentType("text/plain");
        objectApi.put("myfolder/chunked.txt", payload);
        SwiftObject object = objectApi.getWithoutBody("myfolder/chunked.txt");
        assertNotNull(object);
    }

    @Test
    @SuppressWarnings("resource")
    public void t15GetWithBody() throws IOException {
        ObjectApi objectApi = swiftApi.getObjectApi(region, "container1");
        SwiftObject object = objectApi.get("myfolder/chunked.txt");
        String response = new Scanner(object.getPayload().openStream()).nextLine();
        assertEquals("foo1 foo2 foo3 foo4", response);
    }

    @Test
    public void t16GetWithBodyRangeRequest() throws IOException {
        ObjectApi objectApi = swiftApi.getObjectApi(region, "container1");

        GetOptions options = new GetOptions();
        options.range(0, 3);
        options.range(14, 19);
        SwiftObject object = objectApi.get("myfolder/chunked.txt", options);

        String multipartBody = toString(object.getPayload().openStream());

        String ct = object.getPayload().getContentMetadata().getContentType();
        assertTrue(ct.startsWith("multipart/byteranges"));

        String boundary = ct.substring(ct.lastIndexOf("=") + 1, ct.length());

        StringBuilder builder = new StringBuilder();
        builder.append("\r\n");
        builder.append("--boundary").append("\r\n");
        builder.append("Content-Type: text/plain").append("\r\n");
        builder.append("Content-Range: bytes 0-3/4").append("\r\n");
        builder.append("\r\n");
        builder.append("foo1").append("\r\n");
        builder.append("--boundary").append("\r\n");
        builder.append("Content-Type: text/plain").append("\r\n");
        builder.append("Content-Range: bytes 14-18/5").append("\r\n");
        builder.append("\r\n");
        builder.append(" foo4").append("\r\n");
        builder.append("--boundary--").append("\r\n");

        String expectedMultipartBody = builder.toString().replace("boundary", boundary);

        assertEquals(expectedMultipartBody, multipartBody);

        GetOptions options2 = new GetOptions();
        options2.getRanges().add("-1");
        SwiftObject swiftObject = objectApi.get("myfolder/chunked.txt", options2);
        String response2 = toString(swiftObject.getPayload().openStream());
        assertEquals("4", response2);

        GetOptions options3 = new GetOptions();
        options3.buildRequestHeaders().put("Range", "bytes=1-");
        SwiftObject swiftObject2 = objectApi.get("myfolder/chunked.txt", options3);
        String response3 = toString(swiftObject2.getPayload().openStream());
        assertEquals("oo1 foo2 foo3 foo4", response3);
    }

    @Test
    public void t17GetWithBodyRangeRequest() throws IOException {
        ObjectApi objectApi = swiftApi.getObjectApi(region, "container1");
        SwiftObject swiftObject = objectApi.get("hello.manifest");
        assertNotNull(swiftObject);
    }

    @Test
    public void t18TemporaryUrl() {
        Map<String, String> metadata = new HashMap<>();
        metadata.put(SwiftHeaders.ACCOUNT_TEMPORARY_URL_KEY, "test-2test-3");
        swiftApi.getAccountApi("default").updateMetadata(metadata);
        HttpRequest signGetBlob = blobStore.getContext().getSigner().signGetBlob("container1", "myfolder/chunked.txt");
        assertTrue(signGetBlob.getEndpoint().toString().contains("temp_url_expires"));
        try {
            InputStream is = signGetBlob.getEndpoint().toURL().openStream();
            String str = toString(is);
            assertEquals("foo1 foo2 foo3 foo4", str);
        } catch (IOException e) {
            throw new CormorantException(e);
        }
    }

    @Test
    public void t19DeleteContainer() {
        containerApi.create("container2");
        Container container = containerApi.get("container2");
        assertNotNull(container);
        assertEquals(container.getBytesUsed().longValue(), 0L);
        assertEquals(container.getObjectCount().longValue(), 0L);
        assertEquals("container2", container.getName());
        containerApi.deleteIfEmpty("container2");
    }

    protected String toString(final InputStream is) {
        final char[] buffer = new char[1024];
        final StringBuilder out = new StringBuilder();
        try (Reader in = new InputStreamReader(is, UTF_8)) {
            for (;;) {
                int rsz = in.read(buffer, 0, buffer.length);
                if (rsz < 0)
                    break;
                out.append(buffer, 0, rsz);
            }
        }
        catch (UnsupportedEncodingException ex) {
            /* ... */
        }
        catch (IOException ex) {
            /* ... */
        }
        return out.toString();
    }
}
