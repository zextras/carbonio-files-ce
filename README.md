<!--
SPDX-FileCopyrightText: 2022 Zextras <https://www.zextras.com>

SPDX-License-Identifier: AGPL-3.0-only
-->

<div align="center">
  <h1>Carbonio Files CE</h1>
</div>

<div align="center">

Files-CE backend service for Zextras Carbonio

[![Contributors][contributors-badge]][contributors]
[![Activity][activity-badge]][activity]
[![License][license-badge]](COPYING)
[![Project][project-badge]][project]
[![Twitter][twitter-badge]][twitter]

</div>

## Dependencies 🔗

The following dependencies are required to run the service correctly but they are not installed by the package.
They must be installed if Mandatory otherwise user discretion is advised.

| Name                     | Mandatory/Optional |
|--------------------------|--------------------|
| carbonio-proxy | Mandatory |
| [carbonio-files-db](https://github.com/Zextras/carbonio-files-db) | Mandatory |
| [carbonio-user-management](https://github.com/Zextras/carbonio-user-management) | Mandatory |
| carbonio-storages-ce     | Mandatory          |
| [carbonio-preview-ce](https://github.com/Zextras/carbonio-preview-ce) | Optional |

## How to install 🏁
Install `carbonio-files-ce` via apt:

```bash
sudo apt install carbonio-files-ce
```

or via yum:

```bash
sudo yum install carbonio-files-ce
```

After the installation you must run `pending-setups` in order to register the service in `service-discover`.

## How to build ⚙
Build using maven:
```bash
mvn clean install
```
It will generate a fat-jar inside the `boot/target` folder.

## How to run 🚀
With the generated fat-jar:
```bash
java -Djava.net.preferIPv4Stack=true -jar boot/target/carbonio-files-ce-*-jar-with-dependencies.jar
```
## License 📚

Files-CE backend service for Zextras Carbonio.

Released under the AGPL-3.0-only license as specified here: [COPYING](COPYING).

Copyright (C) 2022 Zextras <https://www.zextras.com>

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.

See [COPYING](COPYING) file for the project license details

See [THIRDPARTIES](THIRDPARTIES) file for other licenses details

### Copyright notice

All non-software material (such as, for example, names, images, logos, sounds) is owned by Zextras
s.r.l. and is licensed under [CC-BY-NC-SA](https://creativecommons.org/licenses/by-nc-sa/4.0/).

Where not specified, all source files owned by Zextras s.r.l. are licensed under AGPL-3.0-only


[contributors-badge]: https://img.shields.io/github/contributors/zextras/carbonio-files-ce "Contributors"

[contributors]: https://github.com/zextras/carbonio-files-ce/graphs/contributors "Contributors"

[activity-badge]: https://img.shields.io/github/commit-activity/m/zextras/carbonio-files-ce "Activity"

[activity]: https://github.com/zextras/carbonio-files-ce/pulse "Activity"

[license-badge]: https://img.shields.io/badge/license-AGPL%203-green "License AGPL 3"

[project-badge]: https://img.shields.io/badge/project-carbonio-informational "Project Carbonio"

[project]: https://www.zextras.com/carbonio/ "Project Carbonio"

[twitter-badge]: https://img.shields.io/twitter/follow/zextras?style=social&logo=twitter "Follow on Twitter"

[twitter]: https://twitter.com/intent/follow?screen_name=zextras "Follow Zextras on Twitter"