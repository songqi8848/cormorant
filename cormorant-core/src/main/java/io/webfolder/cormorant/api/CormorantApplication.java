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
package io.webfolder.cormorant.api;

import static io.webfolder.cormorant.api.metadata.CacheNames.ACCOUNT;
import static io.webfolder.cormorant.api.metadata.CacheNames.CONTAINER;
import static io.webfolder.cormorant.api.metadata.CacheNames.OBJECT;
import static io.webfolder.cormorant.api.metadata.CacheNames.OBJECT_SYS;
import static io.webfolder.cormorant.api.metadata.MetadataStorage.SQLite;
import static java.util.concurrent.TimeUnit.DAYS;
import static net.jodah.expiringmap.ExpirationPolicy.CREATED;
import static net.jodah.expiringmap.ExpiringMap.builder;

import java.nio.file.Path;
import java.security.Principal;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.ws.rs.core.Application;

import io.webfolder.cormorant.api.fs.PathContainerService;
import io.webfolder.cormorant.api.fs.PathObjectService;
import io.webfolder.cormorant.api.metadata.DefaultMetadataServiceFactory;
import io.webfolder.cormorant.api.metadata.MetadataServiceFactory;
import io.webfolder.cormorant.api.metadata.MetadataStorage;
import io.webfolder.cormorant.api.service.AccountService;
import io.webfolder.cormorant.api.service.KeystoneService;
import io.webfolder.cormorant.api.service.ContainerService;
import io.webfolder.cormorant.api.service.MetadataService;
import io.webfolder.cormorant.api.service.ObjectService;
import io.webfolder.cormorant.internal.jaxrs.AccountController;
import io.webfolder.cormorant.internal.jaxrs.AuthenticationController;
import io.webfolder.cormorant.internal.jaxrs.ContainerController;
import io.webfolder.cormorant.internal.jaxrs.FaviconController;
import io.webfolder.cormorant.internal.jaxrs.HealthCheckController;
import io.webfolder.cormorant.internal.jaxrs.ObjectController;

public class CormorantApplication extends Application {

    private final Path           objectStore;

    private final Path           metadataStore;

    private final AccountService accountService;

    private final KeystoneService keystoneService;

    private final String          accountName;

    private MetadataStorage       metadataStorage;

    private boolean               cacheMetadata;

    private int                   pathMaxCount;

    public CormorantApplication(
                final Path            objectStore,
                final Path            metadataStore,
                final AccountService  accountService,
                final KeystoneService keystoneService,
                final String          accountName) {
        this.objectStore     = objectStore;
        this.metadataStore   = metadataStore;
        this.accountService  = accountService;
        this.keystoneService = keystoneService;
        this.accountName     = accountName;
        setPathMaxCount(10_000);
        setMetadataStorage(SQLite);
        setCacheMetadata(false);
    }

    @Override
    public Set<Object> getSingletons() {
        final Set<Object> singletons = new HashSet<>();

        final MetadataServiceFactory metadataServiceFactory = new DefaultMetadataServiceFactory(metadataStore, getMetadataStorage());

        final MetadataService accountMetadataService   = metadataServiceFactory.create(ACCOUNT   , isCacheMetadata());
        final MetadataService containerMetadataService = metadataServiceFactory.create(CONTAINER , isCacheMetadata());
        final MetadataService objectMetadataService    = metadataServiceFactory.create(OBJECT    , isCacheMetadata());
        final MetadataService systemMetadataService    = metadataServiceFactory.create(OBJECT_SYS, isCacheMetadata());

        final ContainerService<Path> containerService = new PathContainerService(objectStore, pathMaxCount, containerMetadataService, systemMetadataService);
        final ObjectService<Path>    objectService    = new PathObjectService(containerService, systemMetadataService);

        containerService.setObjectService(objectService);

        final Map<String, Principal> tokens = builder()
                                                .expirationPolicy(CREATED)
                                                .expiration(1, DAYS)
                                                .maxSize(100_000)
                                            .build();

        singletons.add(new HealthCheckController());

        singletons.add(new CormorantFeature<>(tokens, keystoneService, accountMetadataService, containerService));

        singletons.add(new AuthenticationController(tokens, keystoneService, accountName));

        singletons.add(new AccountController(accountService,
                                                    accountMetadataService));

        singletons.add(new ContainerController<Path>(accountService,
                                                    containerService,
                                                    containerMetadataService));

        singletons.add(new ObjectController<Path>(accountService,
                                                    containerService,
                                                    objectService,
                                                    objectMetadataService,
                                                    systemMetadataService));

        singletons.add(new FaviconController());

        return singletons;
    }

    public int getPathMaxCount() {
        return pathMaxCount;
    }

    public void setPathMaxCount(int pathMaxCount) {
        this.pathMaxCount = pathMaxCount;
    }

    public MetadataStorage getMetadataStorage() {
        return metadataStorage;
    }

    public void setMetadataStorage(MetadataStorage metadataStorage) {
        this.metadataStorage = metadataStorage;
    }

    public boolean isCacheMetadata() {
        return cacheMetadata;
    }

    public void setCacheMetadata(boolean cacheMetadata) {
        this.cacheMetadata = cacheMetadata;
    }
}
