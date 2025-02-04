package com.cloudogu.gitops.scmm.api

import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.*;

interface GitlabApi {
    /**
     * Creates a new group in GitLab.
     *
     * @param group The GitLabGroup object containing group details.
     * @return The created group response.
     */
    @POST("groups")
    Call<ResponseBody> createGroup(@Body GitLabGroup group);

    /**
     * Create a new project (repository) in an existing group.
     *
     * @param project The project details.
     * @return The created project.
     */
    @POST("projects")
    Call<ResponseBody> createProject(@Body GitLabProject project);

    /**
     * Add a user to a project with specific access permissions.
     *
     * @param projectId   The ID of the project.
     * @param projectMember The project member details, including user ID and access level.
     * @return Response indicating the result of the permission assignment.
     */
    @POST("projects/{id}/members")
    Call<ResponseBody> addProjectMember(@Path("id") int projectId, @Body GitLabMember projectMember);

    @GET("groups/{groupName}")
    Call<ResponseBody> getGroupByName(@Path("groupName") String groupName);

}