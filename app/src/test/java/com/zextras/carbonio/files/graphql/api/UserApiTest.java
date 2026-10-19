// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zextras.carbonio.files.dal.dao.UserId;
import com.zextras.carbonio.files.dal.dao.UserInfo;
import com.zextras.carbonio.files.dal.repositories.interfaces.UserRepository;
import com.zextras.carbonio.files.graphql.auth.AuthenticatedUserProducer;
import com.zextras.carbonio.files.graphql.errors.FilesGraphQLException;
import com.zextras.carbonio.files.graphql.errors.InputValidationException;
import com.zextras.carbonio.files.graphql.model.FolderModel;
import com.zextras.carbonio.files.graphql.model.NodeModel;
import com.zextras.carbonio.files.graphql.model.NodeType;
import com.zextras.carbonio.files.graphql.model.ShareModel;
import com.zextras.carbonio.files.graphql.model.SharePermission;
import com.zextras.carbonio.files.graphql.model.SharedTarget;
import com.zextras.carbonio.files.graphql.model.UserModel;
import com.zextras.carbonio.files.graphql.support.RequestUserCache;
import com.zextras.carbonio.files.graphql.validation.GraphQLInputValidator;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UserApiTest {

  private static final String COOKIES = "ZX_AUTH_TOKEN=test-token";
  private static final String USER_ID = "user-id-1";
  private static final String EMAIL = "test@example.com";

  private UserRepository userRepository;
  private AuthenticatedUserProducer producer;
  private UserApi userApi;

  @BeforeEach
  void setUp() {
    userRepository = mock(UserRepository.class);
    producer = mock(AuthenticatedUserProducer.class);
    when(producer.cookies()).thenReturn(COOKIES);

    userApi = spy(new UserApi());
    userApi.userRepository = userRepository;
    userApi.userCache = new RequestUserCache();
    userApi.producer = producer;
    userApi.validator = new GraphQLInputValidator();
    requested();
  }

  private UserInfo makeUserInfo(String id, String email, String fullName) {
    UserInfo info = new UserInfo();
    info.setId(new UserId(id));
    info.setEmail(email);
    info.setFullName(fullName);
    return info;
  }

  @Test
  void getAccountsByEmail_mixedValidAndInvalid_validatesAllBeforeAnyLookup() {
    assertThatThrownBy(
            () ->
                userApi.getAccountsByEmail(List.of(EMAIL, "bad-one", "other@example.com", "bad2")))
        .isInstanceOf(InputValidationException.class)
        .hasMessage("Invalid Email\nInvalid Email");

    verify(userRepository, never()).getUserByEmail(anyString(), anyString());
  }

  // ─── @Source batch resolvers ──────────────────────────────────────────────

  private NodeModel makeNode(String id, String creatorId, String ownerId, String lastEditorId) {
    return new FolderModel(
        id,
        "parent-id",
        ownerId,
        creatorId,
        lastEditorId,
        0L,
        0L,
        "name",
        "",
        NodeType.FOLDER,
        false,
        "root-id");
  }

  private void requested(String... ids) {
    doReturn(new LinkedHashSet<>(List.of(ids))).when(userApi).requestedNodeUserIds();
  }

  private UserInfo makeUserInfo(String id) {
    return makeUserInfo(id, id + "@example.com", "User " + id);
  }

  @Test
  void aUserMissingFromTheBatchIsCachedAsNotFoundOnlyByItsOwnLookup() {
    requested("uid-A", "ghost");
    NodeModel node = makeNode("n1", "uid-A", "uid-A", "ghost");
    when(userRepository.getUsers(anyList())).thenReturn(List.of(makeUserInfo("uid-A")));

    userApi.creators(List.of(node));
    userApi.owners(List.of(node));
    List<?> lastEditors = userApi.lastEditors(List.of(node));
    userApi.lastEditors(List.of(node));

    verify(userRepository, times(2)).getUsers(anyList());
    verify(userRepository).getUsers(List.of("uid-A", "ghost"));
    verify(userRepository).getUsers(List.of("ghost"));
    assertThat(lastEditors.get(0)).isInstanceOf(FilesGraphQLException.class);
  }

  @Test
  void aFailedLookupDoesNotPoisonTheCacheForLaterFields() {
    NodeModel node = makeNode("n1", "uid-A", "uid-A", null);
    when(userRepository.getUsers(anyList()))
        .thenThrow(new RuntimeException("um down"))
        .thenReturn(List.of(makeUserInfo("uid-A")));

    assertThatThrownBy(() -> userApi.creators(List.of(node))).isInstanceOf(RuntimeException.class);
    List<UserModel> owners = userApi.owners(List.of(node));

    assertThat(owners.get(0).getId()).isEqualTo("uid-A");
  }

  @Test
  void creators_150DistinctIds_partitionsInto2Calls() {
    List<NodeModel> nodes =
        IntStream.range(0, 150)
            .mapToObj(i -> makeNode("n" + i, "uid-" + i, "uid-" + i, null))
            .toList();

    when(userRepository.getUsers(anyList())).thenReturn(Collections.emptyList());

    userApi.creators(nodes);

    verify(userRepository, times(2)).getUsers(anyList());
  }

  @Test
  void shareTargets_multipleShares_coalesceIntoSingleUserLookup() {
    ShareModel s1 = new ShareModel(1000L, SharePermission.READ_ONLY, null, "node-id", USER_ID);
    ShareModel s2 = new ShareModel(1000L, SharePermission.READ_ONLY, null, "node-id", "user-id-2");
    when(userRepository.getUsers(List.of(USER_ID, "user-id-2")))
        .thenReturn(
            List.of(
                makeUserInfo(USER_ID, EMAIL, "Test User"),
                makeUserInfo("user-id-2", "two@example.com", "User Two")));

    List<SharedTarget> result = userApi.shareTargets(List.of(s1, s2));

    assertThat(result).hasSize(2);
    assertThat(((UserModel) result.get(0)).getId()).isEqualTo(USER_ID);
    assertThat(((UserModel) result.get(1)).getId()).isEqualTo("user-id-2");
    verify(userRepository, times(1)).getUsers(anyList());
  }
}
