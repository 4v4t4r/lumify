package io.lumify.web.clientapi;

import io.lumify.web.clientapi.model.*;
import org.apache.commons.io.IOUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.URLEncoder;
import java.util.*;

public abstract class LumifyWebClient {
    private String csrfToken;
    private String currentWorkspaceId;

    public UserMeResponse userMe() {
        JSONObject responseJson = httpGetJson("/user/me");
        UserMeResponse userMeResponse = new UserMeResponse(responseJson);
        csrfToken = userMeResponse.getCsrfToken();
        currentWorkspaceId = userMeResponse.getCurrentWorkspaceId();
        return userMeResponse;
    }

    public WorkspacesResponse workspaces() {
        return new WorkspacesResponse(httpGetJson("/workspaces"));
    }

    public WorkspaceNewResponse workspaceNew() {
        return new WorkspaceNewResponse(httpPostJson("/workspace/new"));
    }

    public WorkspaceDiffResponse workspaceDiff() {
        return new WorkspaceDiffResponse(httpGetJson("/workspace/diff"));
    }

    public WorkspacePublishResponse workspacePublishAll(WorkspaceDiffItem[] diffs) {
        List<PublishItem> publishItems = new ArrayList<PublishItem>();
        for (WorkspaceDiffItem diffItem : diffs) {
            if (diffItem instanceof VertexDiffItem) {
                VertexDiffItem vertexDiffItem = (VertexDiffItem) diffItem;
                publishItems.add(new VertexPublishItem(vertexDiffItem.getVertexId()));
            } else if (diffItem instanceof PropertyDiffItem) {
                PropertyDiffItem propertyDiffItem = (PropertyDiffItem) diffItem;
                publishItems.add(new PropertyPublishItem(propertyDiffItem.getElementId(), propertyDiffItem.getPropertyKey(), propertyDiffItem.getPropertyName()));
            } else {
                throw new LumifyClientApiException("Unhandled diff item type: " + diffItem.getClass().getName());
            }
        }
        return workspacePublish(publishItems);
    }

    public WorkspacePublishResponse workspacePublish(Iterable<PublishItem> publishItems) {
        WorkspacePublishResponse response;
        try {
            JSONArray json = new JSONArray();
            for (PublishItem publishItem : publishItems) {
                json.put(publishItem.getJson());
            }
            ByteArrayInputStream content = new ByteArrayInputStream(("publishData=" + URLEncoder.encode(json.toString(), "UTF8")).getBytes());
            response = new WorkspacePublishResponse(httpPostJson("/workspace/publish", content));
        } catch (Exception ex) {
            throw new LumifyClientApiException("Could not publish workspace", ex);
        }
        if (response.getFailures().length > 0) {
            throw new LumifyClientApiPublishException(response);
        }
        return response;
    }

    public ArtifactImportResponse artifactImport(String visibilitySource, String fileName, InputStream file) {
        try {
            String boundary = "sdlfkgjmwerijlsslkdfj";

            Map<String, List<String>> headers = new HashMap<String, List<String>>();
            headers.put("Content-Type", Arrays.asList("multipart/form-data;boundary=" + boundary));

            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            appendMulipartFormData(buffer, boundary, "visibilitySource", null, new ByteArrayInputStream(visibilitySource.getBytes()), false);
            appendMulipartFormData(buffer, boundary, "file", fileName, file, true);

            InputStream content = new ByteArrayInputStream(buffer.toByteArray());

            return new ArtifactImportResponse(httpPostJson("/artifact/import", headers, content));
        } catch (Exception ex) {
            throw new LumifyClientApiException("Could not import artifact", ex);
        }
    }

    public WorkspaceVerticesResponse workspaceVertices() {
        return new WorkspaceVerticesResponse(httpGetJsonArray("/workspace/vertices"));
    }

    public WorkspaceUpdateResponse workspaceUpdate(List<WorkspaceUpdateItem> workspaceUpdateItems) {
        try {
            JSONObject json = new JSONObject();
            JSONArray entityUpdates = new JSONArray();
            json.put("entityUpdates", entityUpdates);
            JSONArray entityDeletes = new JSONArray();
            json.put("entityDeletes", entityDeletes);
            JSONArray userUpdates = new JSONArray();
            json.put("userUpdates", userUpdates);
            JSONArray userDeletes = new JSONArray();
            json.put("userDeletes", userDeletes);
            for (WorkspaceUpdateItem workspaceUpdateItem : workspaceUpdateItems) {
                if (workspaceUpdateItem instanceof VertexWorkspaceUpdateItem) {
                    entityUpdates.put(workspaceUpdateItem.getJson());
                } else {
                    throw new LumifyClientApiException("Unhandled workspace update item type: " + workspaceUpdateItem.getClass().getName());
                }
            }
            ByteArrayInputStream content = new ByteArrayInputStream(("data=" + URLEncoder.encode(json.toString(), "UTF8")).getBytes());
            return new WorkspaceUpdateResponse(httpPostJson("/workspace/update", content));
        } catch (Exception ex) {
            throw new LumifyClientApiException("Could not update workspace", ex);
        }
    }

    public void logInToCurrentWorkspace() {
        UserMeResponse userMeResponse = userMe();

        WorkspacesResponse workspacesResponse = workspaces();

        Workspace currentWorkspace;
        Workspace[] workspaces = workspacesResponse.getWorkspaces();
        if (workspaces.length == 0) {
            WorkspaceNewResponse workspaceNewResponse = workspaceNew();
            currentWorkspace = workspaceNewResponse.getWorkspace();
        } else {
            if (userMeResponse.getCurrentWorkspaceId() == null) {
                currentWorkspace = workspaces[0];
            } else {
                currentWorkspace = null;
                for (Workspace w : workspaces) {
                    if (w.getId().equals(userMeResponse.getCurrentWorkspaceId())) {
                        currentWorkspace = w;
                        break;
                    }
                }
                if (currentWorkspace == null) {
                    currentWorkspace = workspaces[0];
                }
            }
        }

        setCurrentWorkspaceId(currentWorkspace.getId());
    }

    public void logOut() {
        httpPostJson("/logout");
    }

    private void appendMulipartFormData(OutputStream buffer, String boundary, String fieldName, String fileName, InputStream fieldData, boolean lastPart) throws IOException {
        buffer.write(("--" + boundary + "\r\n").getBytes());
        buffer.write(("Content-Disposition: form-data; name=\"" + fieldName + "\"" + (fileName == null ? "" : ";filename=\"" + fileName + "\"") + "\r\n").getBytes());
        buffer.write("\r\n".getBytes());
        IOUtils.copy(fieldData, buffer);
        buffer.write("\r\n".getBytes());
        buffer.write(("--" + boundary + (lastPart ? "--" : "") + "\r\n").getBytes());
    }

    protected JSONObject httpPostJson(String uri) {
        return httpPostJson(uri, null, null);
    }

    protected JSONObject httpPostJson(String uri, InputStream content) {
        return httpPostJson(uri, null, content);
    }

    protected JSONObject httpPostJson(String uri, Map<String, List<String>> additionalHeaders, InputStream content) {
        try {
            HttpResponse response = httpPost(uri, additionalHeaders, content);
            InputStream in = response.getInputStream();
            String str = IOUtils.toString(in);
            return new JSONObject(str);
        } catch (Exception ex) {
            throw new LumifyClientApiException("httpGetJson failed", ex);
        }
    }

    protected JSONObject httpGetJson(String uri) {
        try {
            HttpResponse response = httpGet(uri);
            InputStream in = response.getInputStream();
            String str = IOUtils.toString(in);
            return new JSONObject(str);
        } catch (Exception ex) {
            throw new LumifyClientApiException("httpGetJson failed", ex);
        }
    }

    protected JSONArray httpGetJsonArray(String uri) {
        try {
            HttpResponse response = httpGet(uri);
            InputStream in = response.getInputStream();
            String str = IOUtils.toString(in);
            return new JSONArray(str);
        } catch (Exception ex) {
            throw new LumifyClientApiException("httpGetJson failed", ex);
        }
    }

    protected abstract HttpResponse httpGet(String uri);

    protected abstract HttpResponse httpPost(String uri, Map<String, List<String>> additionalHeaders, InputStream content);

    protected String getCsrfToken() {
        return csrfToken;
    }

    /**
     * This sets the current workspace id for requests. This does not change the server state of current workspace id.
     */
    public void setCurrentWorkspaceId(String currentWorkspaceId) {
        this.currentWorkspaceId = currentWorkspaceId;
    }

    public String getCurrentWorkspaceId() {
        return currentWorkspaceId;
    }
}
