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
This is a Quarkus service; the deployable is the `app` module.

JVM build:
```bash
mvn clean install
```
Native build (Mandrel/GraalVM):
```bash
mvn clean install -Dnative
```

## How to run 🚀
JVM mode:
```bash
java -Djava.net.preferIPv4Stack=true -jar app/target/quarkus-app/quarkus-run.jar
```
Native binary (`app/target/*-runner`, the artifact packaged and shipped):
```bash
./app/target/carbonio-files-ce-runner -Djava.net.preferIPv4Stack=true
```
Dev mode with live reload:
```bash
mvn -pl app quarkus:dev
```
## Development 🛠

This repo uses the [`pre-commit`](https://pre-commit.com/) framework (`.pre-commit-config.yaml`).
Install it once per clone:

```bash
pip install --user pre-commit
pre-commit install --hook-type pre-commit --hook-type commit-msg
```

Besides linting/formatting, `pre-commit` locally regenerates the files CI previously
generated and bot-committed (Jenkins now only verifies them):

- `THIRDPARTIES` — regenerated via a vendored copy of jenkins-lib-common's
  license-maven-plugin invocation/template (`.ci/thirdparties/`).
- `package/PKGBUILD` `sha256sums` — verified/autofixed via a vendored copy of
  jenkins-lib-common's `checksum-verify.sh` (`.ci/checksum-verify.sh`); `SKIP` entries
  (build artifacts, or sources whose content embeds `pkgver`) are always preserved.

If a hook regenerates a file, `pre-commit` will fail that commit (by design — it does not
auto-stage changes for you). Review the diff, `git add` the regenerated file(s), and
re-run `git commit`.

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
