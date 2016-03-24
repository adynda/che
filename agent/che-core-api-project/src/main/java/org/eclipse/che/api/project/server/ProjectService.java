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
package org.eclipse.che.api.project.server;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;

import org.apache.commons.fileupload.FileItem;
import org.apache.tika.Tika;
import org.eclipse.che.api.core.BadRequestException;
import org.eclipse.che.api.core.ConflictException;
import org.eclipse.che.api.core.ForbiddenException;
import org.eclipse.che.api.core.NotFoundException;
import org.eclipse.che.api.core.ServerException;
import org.eclipse.che.api.core.UnauthorizedException;
import org.eclipse.che.api.core.model.project.type.Value;
import org.eclipse.che.api.core.notification.EventService;
import org.eclipse.che.api.core.rest.Service;
import org.eclipse.che.api.core.rest.annotations.Description;
import org.eclipse.che.api.core.rest.annotations.GenerateLink;
import org.eclipse.che.api.project.server.notification.ProjectItemModifiedEvent;
import org.eclipse.che.api.project.server.type.ProjectTypeConstraintException;
import org.eclipse.che.api.project.server.type.ProjectTypeRegistry;
import org.eclipse.che.api.project.server.type.ProjectTypeResolution;
import org.eclipse.che.api.project.server.type.ProjectTypeUtils;
import org.eclipse.che.api.project.server.type.ValueStorageException;
import org.eclipse.che.api.project.shared.dto.CopyOptions;
import org.eclipse.che.api.project.shared.dto.ItemReference;
import org.eclipse.che.api.project.shared.dto.MoveOptions;
import org.eclipse.che.api.project.shared.dto.SourceEstimation;
import org.eclipse.che.api.project.shared.dto.TreeElement;
import org.eclipse.che.api.vfs.VirtualFile;
import org.eclipse.che.api.vfs.search.QueryExpression;
import org.eclipse.che.api.vfs.search.SearchResult;
import org.eclipse.che.api.vfs.search.SearchResultEntry;
import org.eclipse.che.api.vfs.search.Searcher;
import org.eclipse.che.api.workspace.shared.dto.ProjectConfigDto;
import org.eclipse.che.api.workspace.shared.dto.SourceStorageDto;
import org.eclipse.che.commons.env.EnvironmentContext;
import org.eclipse.che.commons.lang.ws.rs.ExtMediaType;
import org.eclipse.che.dto.server.DtoFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.validation.constraints.NotNull;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriBuilder;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.eclipse.che.api.project.server.DtoConverter.toProjectConfig;

/**
 * Project API.
 *
 * @author andrew00x
 * @author Eugene Voevodin
 * @author Artem Zatsarynnyi
 * @author Valeriy Svydenko
 * @author Dmitry Shnurenko
 */
@Api(value = "/project", description = "Project REST API")
@Path("/project/{ws-id}")
@Singleton
public class ProjectService extends Service {
    private static final Logger LOG  = LoggerFactory.getLogger(ProjectService.class);
    private static final Tika   TIKA = new Tika();

    private ProjectManager      projectManager;
    private EventService        eventService;

    @Inject
    public ProjectService(ProjectManager projectManager, EventService eventService) {
        this.projectManager = projectManager;
        this.eventService = eventService;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Gets list of projects in root folder",
            response = ProjectConfigDto.class,
            responseContainer = "List")
    @ApiResponses({@ApiResponse(code = 200, message = "OK"),
                   @ApiResponse(code = 500, message = "Server error")})
    @GenerateLink(rel = Constants.LINK_REL_GET_PROJECTS)
    public List<ProjectConfigDto> getProjects(@ApiParam("ID of workspace to get projects")
                                              @PathParam("ws-id") String workspace) throws IOException,
                                                                                           ServerException,
                                                                                           ConflictException,
                                                                                           ForbiddenException {
        return projectManager.getProjects()
                             .stream()
                             .map(registeredProject -> toProjectConfig(registeredProject,
                                                                       workspace,
                                                                       getServiceContext().getServiceUriBuilder()))
                             .collect(Collectors.toList());
    }

    @GET
    @Path("/{path:.*}")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Gets project by ID of workspace and project's path",
            response = ProjectConfigDto.class)
    @ApiResponses({@ApiResponse(code = 200, message = "OK"),
                   @ApiResponse(code = 404, message = "Project with specified path doesn't exist in workspace"),
                   @ApiResponse(code = 403, message = "Access to requested project is forbidden"),
                   @ApiResponse(code = 500, message = "Server error")})
    public ProjectConfigDto getProject(@ApiParam(value = "ID of workspace to get projects", required = true)
                                       @PathParam("ws-id") String workspace,
                                       @ApiParam(value = "Path to requested project", required = true)
                                       @PathParam("path") String path) throws NotFoundException,
                                                                              ForbiddenException,
                                                                              ServerException,
                                                                              ConflictException {
        return toProjectConfig(projectManager.getProject(path), workspace, getServiceContext().getServiceUriBuilder());
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Creates new project",
            response = ProjectConfigDto.class)
    @ApiResponses({@ApiResponse(code = 200, message = "OK"),
                   @ApiResponse(code = 403, message = "Operation is forbidden"),
                   @ApiResponse(code = 409, message = "Project with specified name already exist in workspace"),
                   @ApiResponse(code = 500, message = "Server error")})
    @GenerateLink(rel = Constants.LINK_REL_CREATE_PROJECT)
    /**
     * NOTE: parentPath is added to make a module
     */
    public ProjectConfigDto createProject(@ApiParam(value = "ID of workspace to create project", required = true)
                                          @PathParam("ws-id") String workspace,
                                          @ApiParam(value = "Add to this project as module", required = false)
                                          @Description("descriptor of project") ProjectConfigDto projectConfig) throws ConflictException,
                                                                                                                       ForbiddenException,
                                                                                                                       ServerException,
                                                                                                                       NotFoundException {
        final RegisteredProject project = projectManager.createProject(projectConfig, null);
        final ProjectConfigDto configDto = toProjectConfig(project, workspace, getServiceContext().getServiceUriBuilder());

        eventService.publish(new ProjectCreatedEvent(workspace, project.getPath()));

        // TODO this throws NPE
        //logProjectCreatedEvent(configDto.getName(), configDto.getProjectType());

        return configDto;
    }

    @PUT
    @Path("/{path:.*}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Updates existing project",
            response = ProjectConfigDto.class)
    @ApiResponses({@ApiResponse(code = 200, message = "OK"),
                   @ApiResponse(code = 404, message = "Project with specified path doesn't exist in workspace"),
                   @ApiResponse(code = 403, message = "Operation is forbidden"),
                   @ApiResponse(code = 409, message = "Update operation causes conflicts"),
                   @ApiResponse(code = 500, message = "Server error")})
    public ProjectConfigDto updateProject(@ApiParam(value = "ID of workspace", required = true)
                                          @PathParam("ws-id") String workspace,
                                          @ApiParam(value = "Path to updated project", required = true)
                                          @PathParam("path") String path,
                                          ProjectConfigDto projectConfigDto) throws NotFoundException,
                                                                                    ConflictException,
                                                                                    ForbiddenException,
                                                                                    ServerException,
                                                                                    IOException {
        if (path != null) {
            projectConfigDto.setPath(path);
        }
        return toProjectConfig(projectManager.updateProject(projectConfigDto), workspace, getServiceContext().getServiceUriBuilder());
    }

    @DELETE
    @Path("/{path:.*}")
    @ApiOperation(value = "Delete a resource",
            notes = "Delete resources. If you want to delete a single project, specify project name. If a folder or file needs to " +
                    "be deleted a path to the requested resource needs to be specified")
    @ApiResponses({@ApiResponse(code = 204, message = ""),
                   @ApiResponse(code = 403, message = "User not authorized to call this operation"),
                   @ApiResponse(code = 404, message = "Not found"),
                   @ApiResponse(code = 500, message = "Internal Server Error")})
    public void delete(@ApiParam("Workspace ID")
                       @PathParam("ws-id") String workspace,
                       @ApiParam("Path to a resource to be deleted")
                       @PathParam("path") String path) throws NotFoundException, ForbiddenException, ConflictException, ServerException {
        projectManager.delete(path);
    }

    @GET
    @Path("/estimate/{path:.*}")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Estimates if the folder supposed to be project of certain type",
            response = Map.class)
    @ApiResponses({@ApiResponse(code = 200, message = "OK"),
                   @ApiResponse(code = 404, message = "Project with specified path doesn't exist in workspace"),
                   @ApiResponse(code = 403, message = "Access to requested project is forbidden"),
                   @ApiResponse(code = 500, message = "Server error")})
    public SourceEstimation estimateProject(@ApiParam(value = "ID of workspace to estimate projects", required = true)
                                            @PathParam("ws-id") String workspace,
                                            @ApiParam(value = "Path to requested project", required = true)
                                            @PathParam("path") String path,
                                            @ApiParam(value = "Project Type ID to estimate against", required = true)
                                            @QueryParam("type") String projectType) throws NotFoundException,
                                                                                           ForbiddenException,
                                                                                           ServerException,
                                                                                           ConflictException {
        final ProjectTypeResolution resolution = projectManager.estimateProject(path, projectType);

        final HashMap<String, List<String>> attributes = new HashMap<>();
        for (Map.Entry<String, Value> attr : resolution.getProvidedAttributes().entrySet()) {
            attributes.put(attr.getKey(), attr.getValue().getList());
        }

        return DtoFactory.newDto(SourceEstimation.class)
                         .withType(projectType)
                         .withMatched(resolution.matched())
                         .withAttributes(attributes);
    }

    @GET
    @Path("/resolve/{path:.*}")
    @Produces(MediaType.APPLICATION_JSON)
    public List<SourceEstimation> resolveSources(@ApiParam(value = "ID of workspace to estimate projects", required = true)
                                                 @PathParam("ws-id") String workspace,
                                                 @ApiParam(value = "Path to requested project", required = true)
                                                 @PathParam("path") String path) throws NotFoundException,
                                                                                        ForbiddenException,
                                                                                        ServerException,
                                                                                        ConflictException {
        List<SourceEstimation> estimations = new ArrayList<>();
        for (ProjectTypeResolution resolution : projectManager.resolveSources(path, false)) {
            if (resolution.matched()) {
                final HashMap<String, List<String>> attributes = new HashMap<>();
                for (Map.Entry<String, Value> attr : resolution.getProvidedAttributes().entrySet()) {
                    attributes.put(attr.getKey(), attr.getValue().getList());
                }
                estimations.add(DtoFactory.newDto(SourceEstimation.class)
                                          .withType(resolution.getType())
                                          .withMatched(resolution.matched())
                                          .withAttributes(attributes));
            }
        }

        return estimations;
    }

    @POST
    @Path("/import/{path:.*}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Import resource",
            notes = "Import resource. JSON with a designated importer and project location is sent. It is possible to import from " +
                    "VCS or ZIP")
    @ApiResponses({@ApiResponse(code = 204, message = ""),
                   @ApiResponse(code = 401, message = "User not authorized to call this operation"),
                   @ApiResponse(code = 403, message = "Forbidden operation"),
                   @ApiResponse(code = 409, message = "Resource already exists"),
                   @ApiResponse(code = 500, message = "Unsupported source type")})
    public void importProject(@ApiParam(value = "Workspace ID", required = true)
                              @PathParam("ws-id") String workspace,
                              @ApiParam(value = "Path in the project", required = true)
                              @PathParam("path") String path,
                              @ApiParam(value = "Force rewrite existing project", allowableValues = "true,false")
                              @QueryParam("force") boolean force,
                              SourceStorageDto sourceStorage) throws ConflictException,
                                                                     ForbiddenException,
                                                                     UnauthorizedException,
                                                                     IOException,
                                                                     ServerException,
                                                                     NotFoundException,
                                                                     BadRequestException {
        projectManager.importProject(path, sourceStorage);
    }

    @POST
    @Path("/file/{parent:.*}")
    @Consumes({MediaType.MEDIA_TYPE_WILDCARD})
    @Produces({MediaType.APPLICATION_JSON})
    @ApiOperation(value = "Create file",
            notes = "Create a new file in a project. If file type isn't specified the server will resolve its type.")
    @ApiResponses({@ApiResponse(code = 201, message = ""),
                   @ApiResponse(code = 403, message = "User not authorized to call this operation"),
                   @ApiResponse(code = 404, message = "Not found"),
                   @ApiResponse(code = 409, message = "File already exists"),
                   @ApiResponse(code = 500, message = "Internal Server Error")})
    public Response createFile(@ApiParam(value = "Workspace ID", required = true)
                               @PathParam("ws-id") String workspace,
                               @ApiParam(value = "Path to a target directory", required = true)
                               @PathParam("parent") String parentPath,
                               @ApiParam(value = "New file name", required = true)
                               @QueryParam("name") String fileName,
                               InputStream content) throws NotFoundException, ConflictException, ForbiddenException, ServerException {
        final FolderEntry parent = projectManager.asFolder(parentPath);

        if (parent == null) {
            throw new NotFoundException("Parent not found for " + parentPath);
        }

        final FileEntry newFile = parent.createFile(fileName, content);
        final UriBuilder uriBuilder = getServiceContext().getServiceUriBuilder();
        final ItemReference fileReference = DtoConverter.toItemReference(newFile, workspace, uriBuilder.clone());
        final URI location = uriBuilder.clone().path(getClass(), "getFile").build(workspace, newFile.getPath().toString().substring(1));

        eventService.publish(new ProjectItemModifiedEvent(ProjectItemModifiedEvent.EventType.CREATED,
                                                          workspace, projectPath(newFile.getPath().toString()),
                                                          newFile.getPath().toString(),
                                                          false));

        return Response.created(location).entity(fileReference).build();
    }

    @POST
    @Path("/folder/{path:.*}")
    @Produces({MediaType.APPLICATION_JSON})
    @ApiOperation(value = "Create a folder",
            notes = "Create a folder is a specified project")
    @ApiResponses({@ApiResponse(code = 201, message = ""),
                   @ApiResponse(code = 403, message = "User not authorized to call this operation"),
                   @ApiResponse(code = 404, message = "Not found"),
                   @ApiResponse(code = 409, message = "File already exists"),
                   @ApiResponse(code = 500, message = "Internal Server Error")})
    public Response createFolder(@ApiParam(value = "Workspace ID", required = true)
                                 @PathParam("ws-id") String workspace,
                                 @ApiParam(value = "Path to a new folder destination", required = true)
                                 @PathParam("path") String path) throws ConflictException,
                                                                        ForbiddenException,
                                                                        ServerException,
                                                                        NotFoundException {
        final FolderEntry newFolder = projectManager.getProjectsRoot().createFolder(path);
        final UriBuilder uriBuilder = getServiceContext().getServiceUriBuilder();
        final ItemReference folderReference = DtoConverter.toItemReference(newFolder, workspace, uriBuilder.clone());
        final URI location = uriBuilder.clone()
                                       .path(getClass(), "getChildren")
                                       .build(workspace, newFolder.getPath().toString().substring(1));

        eventService.publish(new ProjectItemModifiedEvent(ProjectItemModifiedEvent.EventType.CREATED,
                                                          workspace,
                                                          projectPath(newFolder.getPath().toString()),
                                                          newFolder.getPath().toString(),
                                                          true));

        return Response.created(location).entity(folderReference).build();
    }

    @POST
    @Path("/uploadfile/{parent:.*}")
    @Consumes({MediaType.MULTIPART_FORM_DATA})
    @Produces({MediaType.TEXT_HTML})
    @ApiOperation(value = "Upload a file",
            notes = "Upload a new file")
    @ApiResponses({@ApiResponse(code = 201, message = ""),
                   @ApiResponse(code = 403, message = "User not authorized to call this operation"),
                   @ApiResponse(code = 404, message = "Not found"),
                   @ApiResponse(code = 409, message = "File already exists"),
                   @ApiResponse(code = 500, message = "Internal Server Error")})
    public Response uploadFile(@ApiParam(value = "Workspace ID", required = true)
                               @PathParam("ws-id") String workspace,
                               @ApiParam(value = "Destination path", required = true)
                               @PathParam("parent") String parentPath,
                               Iterator<FileItem> formData) throws NotFoundException,
                                                                   ConflictException,
                                                                   ForbiddenException,
                                                                   ServerException {
        final FolderEntry parent = projectManager.asFolder(parentPath);

        if (parent == null) {
            throw new NotFoundException("Parent not found for " + parentPath);
        }

        return uploadFile(parent.getVirtualFile(), formData);
    }

    @POST
    @Path("/upload/zipfolder/{path:.*}")
    @Consumes({MediaType.MULTIPART_FORM_DATA})
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Upload zip folder",
            notes = "Upload folder from local zip",
            response = Response.class)
    @ApiResponses({@ApiResponse(code = 200, message = ""),
                   @ApiResponse(code = 401, message = "User not authorized to call this operation"),
                   @ApiResponse(code = 403, message = "Forbidden operation"),
                   @ApiResponse(code = 404, message = "Not found"),
                   @ApiResponse(code = 409, message = "Resource already exists"),
                   @ApiResponse(code = 500, message = "Internal Server Error")})
    public Response uploadFolderFromZip(@ApiParam(value = "Workspace ID", required = true)
                                        @PathParam("ws-id") String workspace,
                                        @ApiParam(value = "Path in the project", required = true)
                                        @PathParam("path") String path,
                                        Iterator<FileItem> formData) throws ServerException,
                                                                            ConflictException,
                                                                            ForbiddenException,
                                                                            NotFoundException {
        final FolderEntry parent = projectManager.asFolder(path);

        if (parent == null) {
            throw new NotFoundException("Parent not found for " + path);
        }

        return uploadZip(parent.getVirtualFile(), formData);
    }

    @ApiOperation(value = "Get file content",
            notes = "Get file content by its name")
    @ApiResponses({@ApiResponse(code = 200, message = "OK"),
                   @ApiResponse(code = 403, message = "User not authorized to call this operation"),
                   @ApiResponse(code = 404, message = "Not found"),
                   @ApiResponse(code = 500, message = "Internal Server Error")})
    @GET
    @Path("/file/{path:.*}")
    public Response getFile(@ApiParam(value = "Workspace ID", required = true)
                            @PathParam("ws-id") String workspace,
                            @ApiParam(value = "Path to a file", required = true)
                            @PathParam("path") String path) throws IOException, NotFoundException, ForbiddenException, ServerException {
        final FileEntry file = projectManager.asFile(path);
        return Response.ok().entity(file.getInputStream()).type(TIKA.detect(file.getName())).build();
    }

    @PUT
    @Path("/file/{path:.*}")
    @Consumes({MediaType.MEDIA_TYPE_WILDCARD})
    @ApiOperation(value = "Update file",
            notes = "Update an existing file with new content")
    @ApiResponses({@ApiResponse(code = 200, message = ""),
                   @ApiResponse(code = 403, message = "User not authorized to call this operation"),
                   @ApiResponse(code = 404, message = "Not found"),
                   @ApiResponse(code = 500, message = "Internal Server Error")})
    public Response updateFile(@ApiParam(value = "Workspace ID", required = true)
                               @PathParam("ws-id") String workspace,
                               @ApiParam(value = "Full path to a file", required = true)
                               @PathParam("path") String path,
                               InputStream content) throws NotFoundException, ForbiddenException, ServerException {
        final FileEntry file = projectManager.asFile(path);

        if (file == null) {
            throw new NotFoundException("File not found for " + path);
        }

        file.updateContent(content);

        eventService.publish(new ProjectItemModifiedEvent(ProjectItemModifiedEvent.EventType.UPDATED,
                                                          workspace,
                                                          projectPath(file.getPath().toString()),
                                                          file.getPath().toString(),
                                                          false));

        return Response.ok().build();
    }

    @POST
    @Path("/copy/{path:.*}")
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Copy resource",
            notes = "Copy resource to a new location which is specified in a query parameter")
    @ApiResponses({@ApiResponse(code = 201, message = ""),
                   @ApiResponse(code = 403, message = "User not authorized to call this operation"),
                   @ApiResponse(code = 404, message = "Not found"),
                   @ApiResponse(code = 409, message = "Resource already exists"),
                   @ApiResponse(code = 500, message = "Internal Server Error")})
    public Response copy(@ApiParam("Workspace ID") @PathParam("ws-id") String workspace,
                         @ApiParam("Path to a resource") @PathParam("path") String path,
                         @ApiParam(value = "Path to a new location", required = true) @QueryParam("to") String newParent,
                         CopyOptions copyOptions) throws NotFoundException,
                                                         ForbiddenException,
                                                         ConflictException,
                                                         ServerException {
        final VirtualFileEntry entry = projectManager.asVirtualFileEntry(path);

        // used to indicate over write of destination
        boolean isOverWrite = false;
        // used to hold new name set in request body
        String newName = entry.getName();
        if (copyOptions != null) {
            if (copyOptions.getOverWrite() != null) {
                isOverWrite = copyOptions.getOverWrite();
            }
            if (copyOptions.getName() != null) {
                newName = copyOptions.getName();
            }
        }

        final VirtualFileEntry copy = projectManager.copyTo(path, newParent, newName, isOverWrite);

        final URI location = getServiceContext().getServiceUriBuilder()
                                                .path(getClass(), copy.isFile() ? "getFile" : "getChildren")
                                                .build(workspace, copy.getPath().toString().substring(1));

        if (copy.isFolder()) {
            try {
                final RegisteredProject project = projectManager.getProject(copy.getPath().toString());
                final String name = project.getName();
                final String projectType = project.getProjectType().getId();
                logProjectCreatedEvent(name, projectType);
            } catch (NotFoundException ignore) {
            }
        }

        return Response.created(location).build();
    }

    @POST
    @Path("/move/{path:.*}")
    @Consumes(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Move resource",
            notes = "Move resource to a new location which is specified in a query parameter")
    @ApiResponses({@ApiResponse(code = 201, message = ""),
                   @ApiResponse(code = 403, message = "User not authorized to call this operation"),
                   @ApiResponse(code = 404, message = "Not found"),
                   @ApiResponse(code = 409, message = "Resource already exists"),
                   @ApiResponse(code = 500, message = "Internal Server Error")})
    public Response move(@ApiParam("Workspace ID") @PathParam("ws-id") String workspace,
                         @ApiParam("Path to a resource to be moved") @PathParam("path") String path,
                         @ApiParam("Path to a new location") @QueryParam("to") String newParent,
                         MoveOptions moveOptions) throws NotFoundException, ForbiddenException, ConflictException, ServerException {
        final VirtualFileEntry entry = projectManager.asVirtualFileEntry(path);

        // used to indicate over write of destination
        boolean isOverWrite = false;
        // used to hold new name set in request body
        String newName = entry.getName();
        if (moveOptions != null) {
            if (moveOptions.getOverWrite() != null) {
                isOverWrite = moveOptions.getOverWrite();
            }
            if (moveOptions.getName() != null) {
                newName = moveOptions.getName();
            }
        }

        final VirtualFileEntry move = projectManager.moveTo(path, newParent, newName, isOverWrite);

        final URI location = getServiceContext().getServiceUriBuilder()
                                                .path(getClass(), move.isFile() ? "getFile" : "getChildren")
                                                .build(workspace, move.getPath().toString().substring(1));

        eventService.publish(new ProjectItemModifiedEvent(ProjectItemModifiedEvent.EventType.MOVED,
                                                          workspace,
                                                          projectPath(entry.getPath().toString()),
                                                          entry.getPath().toString(),
                                                          entry.isFolder(),
                                                          path));

        return Response.created(location).build();
    }

    @POST
    @Path("/upload/zipproject/{path:.*}")
    @Consumes({MediaType.MULTIPART_FORM_DATA})
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Upload zip project",
            notes = "Upload project from local zip",
            response = ProjectConfigDto.class)
    @ApiResponses({@ApiResponse(code = 200, message = ""),
                   @ApiResponse(code = 401, message = "User not authorized to call this operation"),
                   @ApiResponse(code = 403, message = "Forbidden operation"),
                   @ApiResponse(code = 409, message = "Resource already exists"),
                   @ApiResponse(code = 500, message = "Unsupported source type")})
    public List<SourceEstimation> uploadProjectFromZip(@ApiParam(value = "Workspace ID", required = true)
                                                       @PathParam("ws-id") String workspace,
                                                       @ApiParam(value = "Path in the project", required = true)
                                                       @PathParam("path") String path,
                                                       @ApiParam(value = "Force rewrite existing project", allowableValues = "true,false")
                                                       @QueryParam("force") boolean force,
                                                       Iterator<FileItem> formData) throws ServerException,
                                                                                           IOException,
                                                                                           ConflictException,
                                                                                           ForbiddenException,
                                                                                           NotFoundException,
                                                                                           BadRequestException {
        // Not all importers uses virtual file system API. In this case virtual file system API doesn't get events and isn't able to set
        final FolderEntry baseProjectFolder = (FolderEntry)getVirtualFile(path, force);

        int stripNumber = 0;
        String projectName = "";
        String projectDescription = "";
        FileItem contentItem = null;

        while (formData.hasNext()) {
            FileItem item = formData.next();
            if (!item.isFormField()) {
                if (contentItem == null) {
                    contentItem = item;
                } else {
                    throw new ServerException("More then one upload file is found but only one is expected. ");
                }
            } else {
                switch (item.getFieldName()) {
                    case ("name"):
                        projectName = item.getString().trim();
                        break;
                    case ("description"):
                        projectDescription = item.getString().trim();
                        break;
                    case ("skipFirstLevel"):
                        stripNumber = Boolean.parseBoolean(item.getString().trim()) ? 1 : 0;
                        break;
                }
            }
        }

        if (contentItem == null) {
            throw new ServerException("Cannot find zip file for upload.");
        }

        try (InputStream zip = contentItem.getInputStream()) {
            baseProjectFolder.getVirtualFile().unzip(zip, true, stripNumber);
        }

        return resolveSources(workspace, path);
    }

    @POST
    @Path("/import/{path:.*}")
    @Consumes(ExtMediaType.APPLICATION_ZIP)
    @ApiOperation(value = "Import zip",
            notes = "Import resources as zip")
    @ApiResponses({@ApiResponse(code = 201, message = ""),
                   @ApiResponse(code = 403, message = "User not authorized to call this operation"),
                   @ApiResponse(code = 404, message = "Not found"),
                   @ApiResponse(code = 409, message = "Resource already exists"),
                   @ApiResponse(code = 500, message = "Internal Server Error")})
    public Response importZip(@ApiParam(value = "Workspace ID", required = true)
                              @PathParam("ws-id") String workspace,
                              @ApiParam(value = "Path to a location (where import to?)")
                              @PathParam("path") String path,
                              InputStream zip,
                              @DefaultValue("false") @QueryParam("skipFirstLevel") Boolean skipFirstLevel) throws NotFoundException,
                                                                                                                  ConflictException,
                                                                                                                  ForbiddenException,
                                                                                                                  ServerException {
        final FolderEntry parent = projectManager.asFolder(path);

        if (parent == null) {
            throw new NotFoundException("Parent not found for " + path);
        }

        importZip(parent.getVirtualFile(), zip, true, skipFirstLevel);

        try {
            final RegisteredProject project = projectManager.getProject(path);
            eventService.publish(new ProjectCreatedEvent(workspace, project.getPath()));
            final String projectType = project.getProjectType().getId();
            logProjectCreatedEvent(path, projectType);
        } catch (NotFoundException ignore) {
        }

        return Response.created(getServiceContext().getServiceUriBuilder()
                                                   .path(getClass(), "getChildren")
                                                   .build(workspace, parent.getPath().toString().substring(1))).build();
    }

    @GET
    @Path("/export/{path:.*}")
    @Produces(ExtMediaType.APPLICATION_ZIP)
    @ApiOperation(value = "Download ZIP",
            notes = "Export resource as zip. It can be an entire project or folder")
    @ApiResponses({@ApiResponse(code = 201, message = ""),
                   @ApiResponse(code = 403, message = "User not authorized to call this operation"),
                   @ApiResponse(code = 404, message = "Not found"),
                   @ApiResponse(code = 500, message = "Internal Server Error")})
    public InputStream exportZip(@ApiParam(value = "Workspace ID", required = true)
                                 @PathParam("ws-id") String workspace,
                                 @ApiParam(value = "Path to resource to be exported")
                                 @PathParam("path") String path) throws NotFoundException, ForbiddenException, ServerException {

        final FolderEntry folder = projectManager.asFolder(path);

        if (folder == null) {
            throw new NotFoundException("Folder not found " + path);
        }

        return folder.getVirtualFile().zip();
    }

    @GET
    @Path("/export/file/{path:.*}")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response exportFile(@ApiParam(value = "Workspace ID", required = true)
                               @PathParam("ws-id") String workspace,
                               @ApiParam(value = "Path to resource to be imported")
                               @PathParam("path") String path) throws NotFoundException, ForbiddenException, ServerException {

        final FileEntry file = projectManager.asFile(path);

        if (file == null) {
            throw new NotFoundException("File not found " + path);
        }

        final VirtualFile virtualFile = file.getVirtualFile();

        return Response.ok(virtualFile.getContent(), TIKA.detect(virtualFile.getName()))
                       .lastModified(new Date(virtualFile.getLastModificationDate()))
                       .header(HttpHeaders.CONTENT_LENGTH, Long.toString(virtualFile.getLength()))
                       .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + virtualFile.getName() + '"')
                       .build();
    }

    @GET
    @Path("/children/{parent:.*}")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Get project children items",
            notes = "Request all children items for a project, such as files and folders",
            response = ItemReference.class,
            responseContainer = "List")
    @ApiResponses({@ApiResponse(code = 200, message = "OK"),
                   @ApiResponse(code = 403, message = "User not authorized to call this operation"),
                   @ApiResponse(code = 404, message = "Not found"),
                   @ApiResponse(code = 500, message = "Internal Server Error")})
    public List<ItemReference> getChildren(@ApiParam(value = "Workspace ID", required = true)
                                           @PathParam("ws-id") String workspace,
                                           @ApiParam(value = "Path to a project", required = true)
                                           @PathParam("parent") String path) throws NotFoundException,
                                                                                    ForbiddenException,
                                                                                    ServerException {
        final FolderEntry folder = projectManager.asFolder(path);

        if (folder == null) {
            throw new NotFoundException("Parent not found for " + path);
        }

        final List<VirtualFileEntry> children = folder.getChildren();
        final ArrayList<ItemReference> result = new ArrayList<>(children.size());
        final UriBuilder uriBuilder = getServiceContext().getServiceUriBuilder();
        for (VirtualFileEntry child : children) {
            if (child.isFile()) {
                result.add(DtoConverter.toItemReference((FileEntry)child, workspace, uriBuilder.clone()));
            } else {
                result.add(DtoConverter.toItemReference((FolderEntry)child, workspace, uriBuilder.clone()));
            }
        }

        return result;
    }

    @GET
    @Path("/tree/{parent:.*}")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Get project tree",
            notes = "Get project tree. Depth is specified in a query parameter",
            response = TreeElement.class)
    @ApiResponses({@ApiResponse(code = 200, message = "OK"),
                   @ApiResponse(code = 403, message = "User not authorized to call this operation"),
                   @ApiResponse(code = 404, message = "Not found"),
                   @ApiResponse(code = 500, message = "Internal Server Error")})
    public TreeElement getTree(@ApiParam(value = "Workspace ID", required = true)
                               @PathParam("ws-id") String workspace,
                               @ApiParam(value = "Path to resource. Can be project or its folders", required = true)
                               @PathParam("parent") String path,
                               @ApiParam(value = "Tree depth. This parameter can be dropped. If not specified ?depth=1 is used by default")
                               @DefaultValue("1") @QueryParam("depth") int depth,
                               @ApiParam(value = "include children files (in addition to children folders). This parameter can be dropped" +
                                                 ". If not specified ?includeFiles=false is used by default")
                               @DefaultValue("false") @QueryParam("includeFiles") boolean includeFiles) throws NotFoundException,
                                                                                                               ForbiddenException,
                                                                                                               ServerException {
        final FolderEntry folder = projectManager.asFolder(path);
        final UriBuilder uriBuilder = getServiceContext().getServiceUriBuilder();

        return DtoFactory.newDto(TreeElement.class)
                         .withNode(DtoConverter.toItemReference(folder, workspace, uriBuilder.clone()))
                         .withChildren(getTree(folder, workspace, depth, includeFiles, uriBuilder));
    }

    @GET
    @Path("/item/{path:.*}")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Get file or folder",
            response = ItemReference.class)
    @ApiResponses({@ApiResponse(code = 200, message = "OK"),
                   @ApiResponse(code = 403, message = "User not authorized to call this operation"),
                   @ApiResponse(code = 404, message = "Not found"),
                   @ApiResponse(code = 500, message = "Internal Server Error")})
    public ItemReference getItem(@ApiParam(value = "Workspace ID", required = true)
                                 @PathParam("ws-id") String workspace,
                                 @ApiParam(value = "Path to resource. Can be project or its folders", required = true)
                                 @PathParam("path") String path) throws NotFoundException,
                                                                        ForbiddenException,
                                                                        ServerException,
                                                                        ValueStorageException,
                                                                        ProjectTypeConstraintException {
        final VirtualFileEntry entry = projectManager.getProjectsRoot().getChild(path);

        if (entry == null) {
            throw new NotFoundException("Project " + path + " was not found");
        }

        final UriBuilder uriBuilder = getServiceContext().getServiceUriBuilder();

        final ItemReference item;
        if (entry.isFile()) {
            item = DtoConverter.toItemReference((FileEntry)entry, workspace, uriBuilder.clone());
        } else {
            item = DtoConverter.toItemReference((FolderEntry)entry, workspace, uriBuilder.clone());
        }

        return item;
    }

    @GET
    @Path("/search/{path:.*}")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Search for resources",
            notes = "Search for resources applying a number of search filters as query parameters",
            response = ItemReference.class,
            responseContainer = "List")
    @ApiResponses({@ApiResponse(code = 200, message = "OK"),
                   @ApiResponse(code = 403, message = "User not authorized to call this operation"),
                   @ApiResponse(code = 404, message = "Not found"),
                   @ApiResponse(code = 409, message = "Conflict error"),
                   @ApiResponse(code = 500, message = "Internal Server Error")})
    public List<ItemReference> search(@ApiParam(value = "Workspace ID", required = true)
                                      @PathParam("ws-id") String workspace,
                                      @ApiParam(value = "Path to resource, i.e. where to search?", required = true)
                                      @PathParam("path") String path,
                                      @ApiParam(value = "Resource name")
                                      @QueryParam("name") String name,
                                      @ApiParam(value = "Search keywords")
                                      @QueryParam("text") String text,
                                      @ApiParam(value = "Maximum items to display. If this parameter is dropped, there are no limits")
                                      @QueryParam("maxItems") @DefaultValue("-1") int maxItems,
                                      @ApiParam(value = "Skip count")
                                      @QueryParam("skipCount") int skipCount) throws NotFoundException,
                                                                                     ForbiddenException,
                                                                                     ConflictException,
                                                                                     ServerException {
        final Searcher searcher;
        try {
            searcher = projectManager.getSearcher();
        } catch (NotFoundException e) {
            LOG.warn(e.getLocalizedMessage());
            return Collections.emptyList();
        }

        if (skipCount < 0) {
            throw new ConflictException(String.format("Invalid 'skipCount' parameter: %d.", skipCount));
        }

        final QueryExpression expr = new QueryExpression()
                .setPath(path.startsWith("/") ? path : ('/' + path))
                .setName(name)
                .setText(text);

        final SearchResult result = searcher.search(expr);

        if (skipCount > 0) {
            if (skipCount > result.getTotalHits()) {
                throw new ConflictException(
                        String.format("'skipCount' parameter: %d is greater then total number of items in result: %d.",
                                      skipCount, result.getTotalHits()));
            }
        }

        final int length = maxItems > 0 ? Math.min(result.getTotalHits(), maxItems) : result.getTotalHits();
        final List<ItemReference> items = new ArrayList<>(length);
        final FolderEntry root = projectManager.getProjectsRoot();
        final UriBuilder uriBuilder = getServiceContext().getServiceUriBuilder();

        List<SearchResultEntry> entries = result.getResults();
        for (int i = skipCount; i < length; i++) {
            final VirtualFileEntry child = root.getChild(entries.get(i).getFilePath());

            if (child != null && child.isFile()) {
                items.add(DtoConverter.toItemReference((FileEntry)child, workspace, uriBuilder.clone()));
            }
        }

        return items;
    }

    private void logProjectCreatedEvent(@NotNull String projectName, @NotNull String projectType) {
        LOG.info("EVENT#project-created# PROJECT#{}# TYPE#{}# WS#{}# USER#{}# PAAS#default#",
                 projectName,
                 projectType,
                 EnvironmentContext.getCurrent().getWorkspaceId(),
                 EnvironmentContext.getCurrent().getUser().getId());
    }

    private String projectPath(String path) {
        int end = path.indexOf("/");
        if (end == -1) {
            return path;
        }

        return path.substring(0, end);
    }

    private VirtualFileEntry getVirtualFile(String path, boolean force) throws ServerException,
                                                                               ForbiddenException,
                                                                               ConflictException,
                                                                               NotFoundException {
        final VirtualFileEntry virtualFile = projectManager.getProjectsRoot().getChild(path);
        if (virtualFile != null && virtualFile.isFile()) {
            // File with same name exist already exists.
            throw new ConflictException(String.format("File with the name '%s' already exists.", path));
        } else {
            if (virtualFile == null) {
                return projectManager.getProjectsRoot().createFolder(path);
            } else if (!force) {
                // Project already exists.
                throw new ConflictException(String.format("Project with the name '%s' already exists.", path));
            }
        }

        return virtualFile;
    }

    private List<TreeElement> getTree(FolderEntry folder,
                                      String workspace,
                                      int depth,
                                      boolean includeFiles,
                                      UriBuilder uriBuilder) throws ServerException, NotFoundException {
        if (depth == 0) {
            return null;
        }

        final List<? extends VirtualFileEntry> children;

        if (includeFiles) {
            children = folder.getChildFoldersFiles();
        } else {
            children = folder.getChildFolders();
        }

        final List<TreeElement> nodes = new ArrayList<>(children.size());
        for (VirtualFileEntry child : children) {
            if (child.isFolder()) {
                nodes.add(DtoFactory.newDto(TreeElement.class)
                                    .withNode(DtoConverter.toItemReference((FolderEntry)child, workspace, uriBuilder.clone()))
                                    .withChildren(getTree((FolderEntry)child, workspace, depth - 1, includeFiles, uriBuilder)));
            } else {
                nodes.add(DtoFactory.newDto(TreeElement.class)
                                    .withNode(DtoConverter.toItemReference((FileEntry)child, workspace, uriBuilder.clone())));
            }
        }

        return nodes;
    }

    /* --------------------------------------------------------------------------- */
    /* TODO check "upload" methods below, they were copied from old VFS as is      */
    /* --------------------------------------------------------------------------- */

    private static Response uploadFile(VirtualFile parent, Iterator<FileItem> formData) throws ForbiddenException,
                                                                                               ConflictException,
                                                                                               ServerException {
        try {
            FileItem contentItem = null;
            String name = null;
            boolean overwrite = false;

            while (formData.hasNext()) {
                FileItem item = formData.next();
                if (!item.isFormField()) {
                    if (contentItem == null) {
                        contentItem = item;
                    } else {
                        throw new ServerException("More then one upload file is found but only one should be. ");
                    }
                } else if ("name".equals(item.getFieldName())) {
                    name = item.getString().trim();
                } else if ("overwrite".equals(item.getFieldName())) {
                    overwrite = Boolean.parseBoolean(item.getString().trim());
                }
            }

            if (contentItem == null) {
                throw new ServerException("Cannot find file for upload. ");
            }
            if (name == null || name.isEmpty()) {
                name = contentItem.getName();
            }

            try {
                try {
                    parent.createFile(name, contentItem.getInputStream());
                } catch (ConflictException e) {
                    if (!overwrite) {
                        throw new ConflictException("Unable upload file. Item with the same name exists. ");
                    }
                    parent.getChild(org.eclipse.che.api.vfs.Path.of(name)).updateContent(contentItem.getInputStream(), null);
                }
            } catch (IOException ioe) {
                throw new ServerException(ioe.getMessage(), ioe);
            }

            return Response.ok("", MediaType.TEXT_HTML).build();
        } catch (ForbiddenException | ConflictException | ServerException e) {
            HtmlErrorFormatter.sendErrorAsHTML(e);
            // never thrown
            throw e;
        }
    }

    private static Response uploadZip(VirtualFile parent, Iterator<FileItem> formData) throws ForbiddenException,
                                                                                              ConflictException,
                                                                                              ServerException {
        try {
            FileItem contentItem = null;
            boolean overwrite = false;
            boolean skipFirstLevel = false;
            while (formData.hasNext()) {
                FileItem item = formData.next();
                if (!item.isFormField()) {
                    if (contentItem == null) {
                        contentItem = item;
                    } else {
                        throw new ServerException("More then one upload file is found but only one should be. ");
                    }
                } else if ("overwrite".equals(item.getFieldName())) {
                    overwrite = Boolean.parseBoolean(item.getString().trim());
                } else if ("skipFirstLevel".equals(item.getFieldName())) {
                    skipFirstLevel = Boolean.parseBoolean(item.getString().trim());
                }
            }
            if (contentItem == null) {
                throw new ServerException("Cannot find file for upload. ");
            }
            try {
                importZip(parent, contentItem.getInputStream(), overwrite, skipFirstLevel);
            } catch (IOException ioe) {
                throw new ServerException(ioe.getMessage(), ioe);
            }
            return Response.ok("", MediaType.TEXT_HTML).build();
        } catch (ForbiddenException | ConflictException | ServerException e) {
            HtmlErrorFormatter.sendErrorAsHTML(e);
            // never thrown
            throw e;
        }
    }

    private static void importZip(VirtualFile parent, InputStream in, boolean overwrite, boolean skipFirstLevel) throws ForbiddenException,
                                                                                                                        ConflictException,
                                                                                                                        ServerException {
        int stripNum = skipFirstLevel ? 1 : 0;
        parent.unzip(in, overwrite, stripNum);
    }
}