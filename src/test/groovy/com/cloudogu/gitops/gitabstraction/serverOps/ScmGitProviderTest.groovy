package com.cloudogu.gitops.gitabstraction.serverOps

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.scmm.api.Permission
import com.cloudogu.gitops.scmm.api.Repository
import com.cloudogu.gitops.scmm.api.RepositoryApi
import com.cloudogu.gitops.scmm.api.ScmmApiClient
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.function.Executable
import org.mockito.ArgumentCaptor
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import retrofit2.Call
import retrofit2.Response

import static org.junit.jupiter.api.Assertions.*
import static org.mockito.ArgumentMatchers.*
import static org.mockito.Mockito.verify
import static org.mockito.Mockito.when

@ExtendWith(MockitoExtension.class)
class ScmGitProviderTest {
    @Mock
    Config config;
    @Mock
    ScmmApiClient scmmApiClient;
    @Mock
    RepositoryApi repositoryApi;
    @Mock
    Call<Void> call

    @InjectMocks
    ScmGitProvider scmGitProvider;

    @BeforeEach
    void setup() {
        when(scmmApiClient.repositoryApi()).thenReturn(repositoryApi);
    }

    // --- createRepository ---
    @Test
    @DisplayName("createRepository 201 returns true and passes namespace/name/description")
    void createRepository_returnsTrueOn201() {
        when(repositoryApi.create(any(Repository), eq(true))).thenReturn(call)
        when(call.execute()).thenReturn(response(201))

        boolean created = scmGitProvider.createRepository('namespace/repo', 'my repo', true)

        assertTrue(created)
        ArgumentCaptor<Repository> repoCap = ArgumentCaptor.forClass(Repository)
        verify(repositoryApi).create(repoCap.capture(), eq(true))
        assertEquals('namespace', repoCap.value.namespace)
        assertEquals('repo', repoCap.value.name)
        assertEquals('my repo', repoCap.value.description)
    }

    @Test
    @DisplayName("createRepository 409 returns false (already exists)")
    void createRepository_returnsFalseOn409() {
        when(repositoryApi.create(any(Repository), eq(false))).thenReturn(call)
        when(call.execute()).thenReturn(response(409))

        boolean created = scmGitProvider.createRepository('namespace/repo', 'test', false)
        assertFalse(created)
    }

    @Test
    @DisplayName("createRepository non-201/409 throws with HTTP details")
    void createRepository_throwsOnOtherCodes() {
        when(repositoryApi.create(any(Repository), anyBoolean())).thenReturn(call)
        when(call.execute()).thenReturn(response(400))

        def ex = assertThrows(RuntimeException) {
            scmGitProvider.createRepository('namespace/repo', 'test', false)
        }
        assertTrue(ex.message.contains('Could not create Repository namespace/repo'))
        assertTrue(ex.message.contains('HTTP Details: 400'))
    }

    @Test
    @DisplayName("createRepository null description becomes empty string")
    void createRepository_defaultsDescriptionToEmptyString() {
        when(repositoryApi.create(any(Repository), eq(true))).thenReturn(call)
        when(call.execute()).thenReturn(response(201))

        boolean created = scmGitProvider.createRepository('namespace/repo', null, true)

        assertTrue(created)
        ArgumentCaptor<Repository> repoCap = ArgumentCaptor.forClass(Repository)
        verify(repositoryApi).create(repoCap.capture(), eq(true))
        assertEquals('', repoCap.value.description)
    }

    // --- setRepositoryPermission ---
    @Test
    @DisplayName("setRepositoryPermission 201 does not throw and sends expected Permission")
    void setRepositoryPermission_noExceptionOn201() {
        when(repositoryApi.createPermission(eq('namespace'), eq('repo'), any(Permission))).thenReturn(call)
        when(call.execute()).thenReturn(response(201))

        scmGitProvider.setRepositoryPermission('namespace/repo', 'devs', Permission.Role.OWNER, true)

        ArgumentCaptor<Permission> permCap = ArgumentCaptor.forClass(Permission)
        verify(repositoryApi).createPermission(eq('namespace'), eq('repo'), permCap.capture())
        assertEquals('devs', permCap.value.name)
        assertEquals(Permission.Role.OWNER, permCap.value.role)
        assertTrue(permCap.value.groupPermission)
    }

    @Test
    @DisplayName("setRepositoryPermission 409 does not throw (already exists)")
    void setRepositoryPermission_noExceptionOn409() {
        when(repositoryApi.createPermission(anyString(), anyString(), any(Permission))).thenReturn(call)
        when(call.execute()).thenReturn(response(409))

        assertDoesNotThrow(({
            scmGitProvider.setRepositoryPermission('namespace/repo', 'user', Permission.Role.READ, false)
        } as Executable))
    }


    @Test
    @DisplayName("setRepositoryPermission other error codes throw with HTTP details")
    void setRepositoryPermission_throwsOnOtherCodes() {
        when(repositoryApi.createPermission(anyString(), anyString(), any(Permission))).thenReturn(call)
        when(call.execute()).thenReturn(response(500))

        def exception = assertThrows(RuntimeException) {
            scmGitProvider.setRepositoryPermission('namespace/repo', 'user', Permission.Role.WRITE, false)
        }
        assertTrue(exception.message.contains('HTTP Details: 500'))
    }

    // --- computePushUrl ---


    private static Response<Void> response(int code) {
        def raw = new okhttp3.Response.Builder()
                .request(new Request.Builder().url('http://localhost/_test').build())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message('X')
                .build()
        if (code >= 200 && code < 300) {
            return Response.success(null, raw)       // e.g. 201
        }
        def errorBody = ResponseBody.create('error', MediaType.get('text/plain'))

        return Response.error(errorBody, raw)        // e.g. 409/400/500
    }
}
