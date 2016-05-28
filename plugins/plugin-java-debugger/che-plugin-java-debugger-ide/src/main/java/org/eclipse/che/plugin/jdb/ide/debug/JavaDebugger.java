/*******************************************************************************
 * Copyright (c) 2012-2016 Codenvy, S.A.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Codenvy, S.A. - initial API and implementation
 *******************************************************************************/
package org.eclipse.che.plugin.jdb.ide.debug;

import com.google.inject.Inject;
import com.google.web.bindery.event.shared.EventBus;

import org.eclipse.che.api.debug.shared.model.Location;
import org.eclipse.che.ide.api.app.AppContext;
import org.eclipse.che.ide.api.debug.BreakpointManager;
import org.eclipse.che.ide.api.debug.DebuggerServiceClient;
import org.eclipse.che.ide.api.filetypes.FileTypeRegistry;
import org.eclipse.che.ide.api.resources.Project;
import org.eclipse.che.ide.api.resources.Resource;
import org.eclipse.che.ide.api.resources.VirtualFile;
import org.eclipse.che.ide.debug.DebuggerDescriptor;
import org.eclipse.che.ide.debug.DebuggerManager;
import org.eclipse.che.ide.dto.DtoFactory;
import org.eclipse.che.ide.ext.java.client.projecttree.JavaSourceFolderUtil;
import org.eclipse.che.ide.util.storage.LocalStorageProvider;
import org.eclipse.che.ide.websocket.MessageBusProvider;
import org.eclipse.che.plugin.debugger.ide.debug.AbstractDebugger;
import org.eclipse.che.plugin.debugger.ide.fqn.FqnResolver;
import org.eclipse.che.plugin.debugger.ide.fqn.FqnResolverFactory;

import javax.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.eclipse.che.plugin.jdb.ide.debug.JavaDebugger.ConnectionProperties.HOST;
import static org.eclipse.che.plugin.jdb.ide.debug.JavaDebugger.ConnectionProperties.PORT;


/**
 * The java debugger.
 *
 * @author Anatoliy Bazko
 */
public class JavaDebugger extends AbstractDebugger {

    public static final String ID = "jdb";

    private final AppContext appContext;

    @Inject
    public JavaDebugger(DebuggerServiceClient service,
                        DtoFactory dtoFactory,
                        LocalStorageProvider localStorageProvider,
                        MessageBusProvider messageBusProvider,
                        EventBus eventBus,
                        FqnResolverFactory fqnResolverFactory,
                        JavaDebuggerFileHandler javaDebuggerFileHandler,
                        DebuggerManager debuggerManager,
                        FileTypeRegistry fileTypeRegistry,
                        BreakpointManager breakpointManager,
                        AppContext appContext) {
        super(service,
              dtoFactory,
              localStorageProvider,
              messageBusProvider,
              eventBus,
              fqnResolverFactory,
              javaDebuggerFileHandler,
              debuggerManager,
              fileTypeRegistry,
              breakpointManager,
              ID);
        this.appContext = appContext;
    }

    @Override
    protected List<String> fqnToPath(@NotNull Location location) {
        final Resource resource = appContext.getResource();

        if (resource == null) {
            return Collections.emptyList();
        }

        final Project project = resource.getRelatedProject().get();

        String pathSuffix = location.getTarget().replace(".", "/") + ".java";

        List<String> sourceFolders = JavaSourceFolderUtil.getSourceFolders(project);
        List<String> filePaths = new ArrayList<>(sourceFolders.size() + 1);

        for (String sourceFolder : sourceFolders) {
            filePaths.add(sourceFolder + pathSuffix);
        }
        filePaths.add(location.getTarget());

        return filePaths;
    }

    @Override
    protected String pathToFqn(VirtualFile file) {
        String fileExtension = fileTypeRegistry.getFileTypeByFile(file).getExtension();

        FqnResolver resolver = fqnResolverFactory.getResolver(fileExtension);
        if (resolver != null) {
            return resolver.resolveFqn(file);
        }

        return null;
    }

    @Override
    protected DebuggerDescriptor toDescriptor(Map<String, String> connectionProperties) {
        String address = connectionProperties.get(HOST.toString()) + ":" + connectionProperties.get(PORT.toString());
        return new DebuggerDescriptor("", address);
    }

    public enum ConnectionProperties {
        HOST,
        PORT
    }
}
