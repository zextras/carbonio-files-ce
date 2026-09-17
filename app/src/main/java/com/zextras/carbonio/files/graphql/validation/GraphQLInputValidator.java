// SPDX-FileCopyrightText: 2026 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.graphql.validation;

import com.zextras.carbonio.files.Constants.Db.RootId;
import com.zextras.carbonio.files.graphql.errors.ErrorCodes;
import com.zextras.carbonio.files.graphql.errors.FilesGraphQLException;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.apache.commons.validator.routines.EmailValidator;

@ApplicationScoped
public class GraphQLInputValidator {

  public Chain checkNodeId(String value) {
    return new Chain().checkNodeId(value);
  }

  public Chain checkNodeIds(List<String> values) {
    return new Chain().checkNodeIds(values);
  }

  public Chain checkNodeName(String value) {
    return new Chain().checkNodeName(value);
  }

  public Chain checkNodeDescription(String value) {
    return new Chain().checkNodeDescription(value);
  }

  public Chain checkUserId(String value) {
    return new Chain().checkUserId(value);
  }

  public Chain checkLinkId(String value) {
    return new Chain().checkLinkId(value);
  }

  public Chain checkLinkDescription(String value) {
    return new Chain().checkLinkDescription(value);
  }

  public Chain checkLinkAccessCode(String value) {
    return new Chain().checkLinkAccessCode(value);
  }

  public Chain checkEmail(String value) {
    return new Chain().checkEmail(value);
  }

  public static final class Chain {

    private static final int LENGTH_NODE_ID = 36;
    private static final int LENGTH_LINK_ID = 36;

    private final List<String> errors = new ArrayList<>();

    public Chain checkNodeId(String value) {
      validateNodeId(value).ifPresent(errors::add);
      return this;
    }

    public Chain checkNodeIds(List<String> values) {
      if (values != null) {
        values.stream()
            .map(this::validateNodeId)
            .filter(Optional::isPresent)
            .map(Optional::get)
            .forEach(errors::add);
      }
      return this;
    }

    public Chain checkNodeName(String value) {
      if (value != null && (value.trim().isEmpty() || value.trim().length() > 1024)) {
        errors.add(
            "Invalid node name. The name cannot be empty, longer than 1024 characters,"
                + " nor be composed only by blank spaces.");
      }
      return this;
    }

    public Chain checkNodeDescription(String value) {
      if (value != null && value.length() > 1024) {
        errors.add("Invalid node description. Length cannot be empty or more than 1024 characters");
      }
      return this;
    }

    public Chain checkUserId(String value) {
      validateUserId(value).ifPresent(errors::add);
      return this;
    }

    public Chain checkLinkId(String value) {
      validateLinkId(value).ifPresent(errors::add);
      return this;
    }

    public Chain checkLinkDescription(String value) {
      if (value != null && value.trim().length() > 300) {
        errors.add(
            "Invalid link description. The description cannot be longer than 300 characters");
      }
      return this;
    }

    public Chain checkLinkAccessCode(String value) {
      if (value != null && !value.isEmpty() && !(value.length() >= 10 && value.length() < 255)) {
        errors.add(
            "Invalid link access code. The access code must be between 10 and 255 characters long");
      }
      return this;
    }

    public Chain checkEmail(String value) {
      validateEmail(value).ifPresent(errors::add);
      return this;
    }

    public void validate() throws FilesGraphQLException {
      if (!errors.isEmpty()) {
        throw FilesGraphQLException.of(
            ErrorCodes.MISSING_FIELD, "errors", String.join("; ", errors));
      }
    }

    private Optional<String> validateNodeId(String nodeId) {
      if (nodeId == null
          || nodeId.equals(RootId.LOCAL_ROOT)
          || nodeId.equals(RootId.TRASH_ROOT)
          || nodeId.length() == LENGTH_NODE_ID) {
        return Optional.empty();
      }
      return Optional.of(
          "Invalid node ID: \"" + nodeId + "\". Length must be " + LENGTH_NODE_ID + " characters");
    }

    private Optional<String> validateUserId(String userId) {
      return (userId == null || !userId.isEmpty())
          ? Optional.empty()
          : Optional.of("Invalid user ID. Length cannot be empty");
    }

    private Optional<String> validateLinkId(String linkId) {
      if (linkId != null && linkId.trim().length() == LENGTH_LINK_ID) {
        return Optional.empty();
      }
      return Optional.of(
          "Invalid link ID: \"" + linkId + "\". Length must be " + LENGTH_LINK_ID + " characters");
    }

    private Optional<String> validateEmail(String email) {
      return EmailValidator.getInstance().isValid(email)
          ? Optional.empty()
          : Optional.of("Invalid Email");
    }
  }
}
