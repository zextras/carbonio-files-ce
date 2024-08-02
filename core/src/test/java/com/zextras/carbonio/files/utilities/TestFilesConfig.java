// SPDX-FileCopyrightText: 2024 Zextras <https://www.zextras.com>
//
// SPDX-License-Identifier: AGPL-3.0-only

package com.zextras.carbonio.files.utilities;

import com.zextras.carbonio.files.config.FilesConfigImpl;

/**
 * Here one can override the standard behaviour of FilesConfigImpl to mock or otherwise differentiate
 * the standard configuration from the test configuration.
 * While here this class doesn't override anything and is basically useless, in the Advanced version
 * it is used, so we should keep this here to have matching structure between the projects.
 */
public class TestFilesConfig extends FilesConfigImpl {

}
