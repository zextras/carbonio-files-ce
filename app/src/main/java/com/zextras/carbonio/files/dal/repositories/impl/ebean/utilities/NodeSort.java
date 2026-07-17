// SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.dal.repositories.impl.ebean.utilities;

import com.zextras.carbonio.files.Constants.Db;
import com.zextras.carbonio.files.dal.dao.ebean.Node;

/**
 * Represents all applicable sort types of a list of {@link Node}s.
 */
public enum NodeSort implements GenericSort {

  // used as last sorting method to discriminate between nodes with identical properties
  ID_ASC {
    @Override
    public String getName() {
      return Db.Node.ID;
    }

    @Override
    public SortOrder getOrder() {
      return SortOrder.ASCENDING;
    }
  },

  LAST_EDITOR_ASC {
    @Override
    public String getName() {
      return Db.Node.EDITOR_ID;
    }

    @Override
    public SortOrder getOrder() {
      return SortOrder.ASCENDING;
    }
  },

  LAST_EDITOR_DESC {
    @Override
    public String getName() {
      return Db.Node.EDITOR_ID;
    }

    @Override
    public SortOrder getOrder() {
      return SortOrder.DESCENDING;
    }
  },

  NAME_ASC {
    @Override
    public String getName() {
      return Db.Node.NAME;
    }

    @Override
    public SortOrder getOrder() {
      return SortOrder.ASCENDING;
    }
  },

  NAME_DESC {
    @Override
    public String getName() {
      return Db.Node.NAME;
    }

    @Override
    public SortOrder getOrder() {
      return SortOrder.DESCENDING;
    }
  },

  OWNER_ASC {
    @Override
    public String getName() {
      return Db.Node.OWNER_ID;
    }

    @Override
    public SortOrder getOrder() {
      return SortOrder.ASCENDING;
    }
  },

  OWNER_DESC {
    @Override
    public String getName() {
      return Db.Node.OWNER_ID;
    }

    @Override
    public SortOrder getOrder() {
      return SortOrder.DESCENDING;
    }
  },

  /** The order is: Root, Folder, File */
  TYPE_ASC {
    @Override
    public String getName() {
      return Db.Node.CATEGORY;
    }

    @Override
    public SortOrder getOrder() {
      return SortOrder.ASCENDING;
    }
  },

  /** The order is: File, Folder, Root */
  TYPE_DESC {
    @Override
    public String getName() {
      return Db.Node.CATEGORY;
    }

    @Override
    public SortOrder getOrder() {
      return SortOrder.DESCENDING;
    }
  },

  UPDATED_AT_ASC {
    @Override
    public String getName() {
      return Db.Node.UPDATED_AT;
    }

    @Override
    public SortOrder getOrder() {
      return SortOrder.ASCENDING;
    }
  },

  UPDATED_AT_DESC {
    @Override
    public String getName() {
      return Db.Node.UPDATED_AT;
    }

    @Override
    public SortOrder getOrder() {
      return SortOrder.DESCENDING;
    }
  },

  CREATED_AT_ASC {
    @Override
    public String getName() {
      return Db.Node.CREATED_AT;
    }

    @Override
    public SortOrder getOrder() {
      return SortOrder.ASCENDING;
    }
  },

  CREATED_AT_DESC {
    @Override
    public String getName() {
      return Db.Node.CREATED_AT;
    }

    @Override
    public SortOrder getOrder() {
      return SortOrder.DESCENDING;
    }
  },

  SIZE_ASC {
    @Override
    public String getName() {
      return Db.Node.SIZE;
    }

    @Override
    public SortOrder getOrder() {
      return SortOrder.ASCENDING;
    }
  },

  SIZE_DESC {
    @Override
    public String getName() {
      return Db.Node.SIZE;
    }

    @Override
    public SortOrder getOrder() {
      return SortOrder.DESCENDING;
    }
  }
}
