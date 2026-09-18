// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zextras.carbonio.files.dal.dao.UserId;
import com.zextras.carbonio.files.dal.dao.UserInfo;
import com.zextras.carbonio.files.dal.dao.UserMyself;
import com.zextras.carbonio.files.dal.repositories.interfaces.UserRepository;
import com.zextras.carbonio.files.graphql.auth.AuthenticatedUserProducer;
import com.zextras.carbonio.files.graphql.errors.ErrorCodes;
import com.zextras.carbonio.files.graphql.errors.FilesGraphQLException;
import com.zextras.carbonio.files.graphql.model.Account;
import com.zextras.carbonio.files.graphql.model.DistributionListModel;
import com.zextras.carbonio.files.graphql.model.FolderModel;
import com.zextras.carbonio.files.graphql.model.NodeModel;
import com.zextras.carbonio.files.graphql.model.NodeType;
import com.zextras.carbonio.files.graphql.model.ShareModel;
import com.zextras.carbonio.files.graphql.model.SharePermission;
import com.zextras.carbonio.files.graphql.model.SharedTarget;
import com.zextras.carbonio.files.graphql.model.UserModel;
import com.zextras.carbonio.files.graphql.validation.GraphQLInputValidator;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UserApiTest {

  private static final String COOKIES = "ZX_AUTH_TOKEN=test-token";
  private static final String REQUESTER_ID = "requester-id";
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

    UserMyself requester = mock(UserMyself.class);
    when(requester.getId()).thenReturn(new UserId(REQUESTER_ID));

    userApi = new UserApi();
    userApi.userRepository = userRepository;
    userApi.producer = producer;
    userApi.validator = new GraphQLInputValidator();
    userApi.requester = requester;
  }

  private UserInfo makeUserInfo(String id, String email, String fullName) {
    UserInfo info = new UserInfo();
    info.setId(new UserId(id));
    info.setEmail(email);
    info.setFullName(fullName);
    return info;
  }

  // ─── getUserById ──────────────────────────────────────────────────────────

  @Test
  void getUserById_happy() throws FilesGraphQLException {
    UserInfo info = makeUserInfo(USER_ID, EMAIL, "Test User");
    when(userRepository.getUserById(COOKIES, USER_ID)).thenReturn(Optional.of(info));

    UserModel result = userApi.getUserById(USER_ID);

    assertThat(result.getId()).isEqualTo(USER_ID);
    assertThat(result.getEmail()).isEqualTo(EMAIL);
    assertThat(result.getFullName()).isEqualTo("Test User");
  }

  @Test
  void getUserById_notFound_throwsAccountNotFound() {
    when(userRepository.getUserById(COOKIES, USER_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> userApi.getUserById(USER_ID))
        .isInstanceOf(FilesGraphQLException.class)
        .satisfies(
            e ->
                assertThat(((FilesGraphQLException) e).getErrorCode())
                    .isEqualTo(ErrorCodes.ACCOUNT_NOT_FOUND));
  }

  // ─── getAccountByEmail ────────────────────────────────────────────────────

  @Test
  void getAccountByEmail_happy() throws FilesGraphQLException {
    UserInfo info = makeUserInfo(USER_ID, EMAIL, "Test User");
    when(userRepository.getUserByEmail(COOKIES, EMAIL)).thenReturn(Optional.of(info));

    Account result = userApi.getAccountByEmail(EMAIL);

    assertThat(result).isInstanceOf(UserModel.class);
    assertThat(((UserModel) result).getEmail()).isEqualTo(EMAIL);
  }

  @Test
  void getAccountByEmail_notFound_throwsAccountNotFound() {
    when(userRepository.getUserByEmail(COOKIES, EMAIL)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> userApi.getAccountByEmail(EMAIL))
        .isInstanceOf(FilesGraphQLException.class)
        .satisfies(
            e ->
                assertThat(((FilesGraphQLException) e).getErrorCode())
                    .isEqualTo(ErrorCodes.ACCOUNT_NOT_FOUND));
  }

  @Test
  void getAccountByEmail_invalidEmail_throwsValidationError() {
    assertThatThrownBy(() -> userApi.getAccountByEmail("not-an-email"))
        .isInstanceOf(FilesGraphQLException.class);
  }

  // ─── getAccountsByEmail ───────────────────────────────────────────────────

  @Test
  void getAccountsByEmail_happy_returnsAllFound() throws FilesGraphQLException {
    String email2 = "other@example.com";
    UserInfo info1 = makeUserInfo(USER_ID, EMAIL, "User One");
    UserInfo info2 = makeUserInfo("user-id-2", email2, "User Two");
    when(userRepository.getUserByEmail(COOKIES, EMAIL)).thenReturn(Optional.of(info1));
    when(userRepository.getUserByEmail(COOKIES, email2)).thenReturn(Optional.of(info2));

    List<Account> result = userApi.getAccountsByEmail(List.of(EMAIL, email2));

    assertThat(result).hasSize(2);
    assertThat(result.get(0)).isInstanceOf(UserModel.class);
    assertThat(((UserModel) result.get(0)).getEmail()).isEqualTo(EMAIL);
    assertThat(result.get(1)).isInstanceOf(UserModel.class);
    assertThat(((UserModel) result.get(1)).getEmail()).isEqualTo(email2);
  }

  @Test
  void getAccountsByEmail_someNotFound_returnsNullSlots() throws FilesGraphQLException {
    String email2 = "missing@example.com";
    UserInfo info1 = makeUserInfo(USER_ID, EMAIL, "User One");
    when(userRepository.getUserByEmail(COOKIES, EMAIL)).thenReturn(Optional.of(info1));
    when(userRepository.getUserByEmail(COOKIES, email2)).thenReturn(Optional.empty());

    List<Account> result = userApi.getAccountsByEmail(List.of(EMAIL, email2));

    assertThat(result).hasSize(2);
    assertThat(result.get(0)).isInstanceOf(UserModel.class);
    assertThat(result.get(1)).isNull();
  }

  @Test
  void getAccountsByEmail_invalidEmail_throwsValidationError() {
    assertThatThrownBy(() -> userApi.getAccountsByEmail(List.of("bad-email")))
        .isInstanceOf(FilesGraphQLException.class);
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

  private UserInfo makeUserInfo(String id) {
    return makeUserInfo(id, id + "@example.com", "User " + id);
  }

  @Test
  void creators_positionalAlignment_nullIdYieldsNullSlot() {
    NodeModel nodeA = makeNode("n1", "uid-A", "owner-A", null);
    NodeModel nodeB = makeNode("n2", null, "owner-B", null);
    NodeModel nodeC = makeNode("n3", "uid-A", "owner-C", null);

    UserInfo uA = makeUserInfo("uid-A", "a@example.com", "User A");
    when(userRepository.getUsers(anyList())).thenReturn(List.of(uA));

    List<UserModel> result = userApi.creators(List.of(nodeA, nodeB, nodeC));

    assertThat(result).hasSize(3);
    assertThat(result.get(0)).isNotNull();
    assertThat(result.get(0).getId()).isEqualTo("uid-A");
    assertThat(result.get(1)).isNull();
    assertThat(result.get(2)).isNotNull();
    assertThat(result.get(2).getId()).isEqualTo("uid-A");
  }

  @Test
  void creators_150DistinctIds_partitionsInto2Calls() {
    List<NodeModel> nodes =
        IntStream.range(0, 150)
            .mapToObj(i -> makeNode("n" + i, "uid-" + i, "owner-" + i, null))
            .toList();

    when(userRepository.getUsers(anyList())).thenReturn(Collections.emptyList());

    userApi.creators(nodes);

    verify(userRepository, times(2)).getUsers(anyList());
  }

  @Test
  void owners_nullIdYieldsNullSlot() {
    NodeModel nodeA = makeNode("n1", "creator-A", "uid-O", null);
    NodeModel nodeB = makeNode("n2", "creator-B", null, null);

    UserInfo uO = makeUserInfo("uid-O", "o@example.com", "Owner");
    when(userRepository.getUsers(anyList())).thenReturn(List.of(uO));

    List<UserModel> result = userApi.owners(List.of(nodeA, nodeB));

    assertThat(result).hasSize(2);
    assertThat(result.get(0).getId()).isEqualTo("uid-O");
    assertThat(result.get(1)).isNull();
  }

  @Test
  void lastEditors_nullIdYieldsNullSlot() {
    NodeModel nodeA = makeNode("n1", "c1", "o1", "uid-E");
    NodeModel nodeB = makeNode("n2", "c2", "o2", null);

    UserInfo uE = makeUserInfo("uid-E", "e@example.com", "Editor");
    when(userRepository.getUsers(anyList())).thenReturn(List.of(uE));

    List<UserModel> result = userApi.lastEditors(List.of(nodeA, nodeB));

    assertThat(result).hasSize(2);
    assertThat(result.get(0).getId()).isEqualTo("uid-E");
    assertThat(result.get(1)).isNull();
  }

  @Test
  void partition_150items_yieldsTwoChunks() {
    List<Integer> list = IntStream.range(0, 150).boxed().toList();
    List<List<Integer>> chunks = UserApi.partition(list, 100);
    assertThat(chunks).hasSize(2);
    assertThat(chunks.get(0)).hasSize(100);
    assertThat(chunks.get(1)).hasSize(50);
  }

  @Test
  void partition_emptyList_yieldsNoChunks() {
    assertThat(UserApi.partition(List.of(), 100)).isEmpty();
  }

  // ─── share_target @Source ─────────────────────────────────────────────────────

  @Test
  void shareTarget_found_returnsUserModel() {
    ShareModel shareModel =
        new ShareModel(1000L, SharePermission.READ_ONLY, null, "node-id", USER_ID);
    UserInfo userInfo = makeUserInfo(USER_ID, EMAIL, "Test User");
    when(userRepository.getUsers(List.of(USER_ID))).thenReturn(List.of(userInfo));

    SharedTarget result = userApi.shareTarget(shareModel);

    assertThat(result).isNotNull().isInstanceOf(UserModel.class);
    assertThat(((UserModel) result).getId()).isEqualTo(USER_ID);
    assertThat(((UserModel) result).getEmail()).isEqualTo(EMAIL);
  }

  @Test
  void shareTarget_notFound_returnsNull() {
    ShareModel shareModel =
        new ShareModel(1000L, SharePermission.READ_ONLY, null, "node-id", "unknown-id");
    when(userRepository.getUsers(List.of("unknown-id"))).thenReturn(List.of());

    SharedTarget result = userApi.shareTarget(shareModel);

    assertThat(result).isNull();
  }

  // ─── users @Source (DistributionList) ────────────────────────────────────────

  @Test
  void users_alwaysReturnsEmptyList() {
    DistributionListModel dl = new DistributionListModel("dl-id", "My DL");

    List<UserModel> result = userApi.users(dl, 10, null);

    assertThat(result).isEmpty();
  }
}
