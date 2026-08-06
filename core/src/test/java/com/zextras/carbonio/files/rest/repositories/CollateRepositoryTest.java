// SPDX-FileCopyrightText: 2025 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.rest.repositories;

import com.zextras.carbonio.files.config.FilesConfig;
import com.zextras.carbonio.files.dal.DatabaseManager;
import com.zextras.carbonio.files.dal.repositories.impl.ebean.CollationRepositoryEbean;
import io.ebean.Database;
import io.ebean.SqlQuery;
import io.ebean.SqlRow;
import java.util.Optional;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class CollateRepositoryTest {

  private DatabaseManager databaseManagerFlywayMock;
  private FilesConfig filesConfigMock;
  private Database databaseMock;

  @BeforeEach
  void setup() {
    databaseManagerFlywayMock = Mockito.mock(DatabaseManager.class);
    filesConfigMock = Mockito.mock(FilesConfig.class);
    databaseMock = Mockito.mock(Database.class);
    Mockito.when(databaseManagerFlywayMock.getEbeanDatabase()).thenReturn(databaseMock);
  }

  @Test
  void givenDefaultCollateIsEnUsUtf8_getValidCollateForQueryShouldReturnEmptyOptional() {
    // Given
    CollationRepositoryEbean collationRepositoryEbean =
        new CollationRepositoryEbean(databaseManagerFlywayMock, filesConfigMock);

    SqlQuery sqlQueryMock = Mockito.mock(SqlQuery.class);
    SqlRow sqlRowMock = Mockito.mock(SqlRow.class);
    Mockito.when(databaseMock.sqlQuery(Mockito.anyString())).thenReturn(sqlQueryMock);
    Mockito.when(sqlQueryMock.setParameter(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(sqlQueryMock);
    Mockito.when(sqlQueryMock.findOne()).thenReturn(sqlRowMock);
    Mockito.when(sqlRowMock.getString("datcollate")).thenReturn("en_US.UTF-8");

    // When
    Optional<String> result = collationRepositoryEbean.getValidCollateForQuery();

    // Then
    Assertions.assertThat(result).isEmpty();
  }

  @Test
  void givenDefaultCollateIsCUtf8_getValidCollateForQueryShouldReturnOptionalWithEnUsUtf8() {
    // Given
    CollationRepositoryEbean collationRepositoryEbean =
        new CollationRepositoryEbean(databaseManagerFlywayMock, filesConfigMock);

    SqlQuery sqlQueryMock = Mockito.mock(SqlQuery.class);
    SqlRow sqlRowMock = Mockito.mock(SqlRow.class);
    Mockito.when(databaseMock.sqlQuery(Mockito.anyString())).thenReturn(sqlQueryMock);
    Mockito.when(sqlQueryMock.setParameter(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(sqlQueryMock);
    Mockito.when(sqlQueryMock.findOne()).thenReturn(sqlRowMock);
    Mockito.when(sqlRowMock.getString("datcollate")).thenReturn("C.UTF-8");
    Mockito.when(sqlRowMock.getInteger("count")).thenReturn(1);

    // When
    Optional<String> result = collationRepositoryEbean.getValidCollateForQuery();

    // Then
    Assertions.assertThat(result).isPresent().contains("\"en_US.utf8\"");
  }
}
