#!/bin/bash
#
# SPDX-FileCopyrightText: 2023 Zextras <https://www.zextras.com>
#
# SPDX-License-Identifier: GPL-2.0-only
#
OS=${1:-"ubuntu-jammy"}

if [[ -z $OS ]]
then
  echo "Please provide an OS as argument: (ubuntu-jammy, rocky-8)"
  exit 1
fi

echo "Building for OS: $OS"

cp ./boot/target/carbonio-files-ce-*-jar-with-dependencies.jar package/carbonio-files.jar
cp ./core/src/main/resources/carbonio-files.properties package/config.properties
cp ./package/watches/* package/

if [[ $OS == "ubuntu-jammy" ]]
then
  docker run -it --rm \
    --entrypoint=yap \
    -v $(pwd)/artifacts/ubuntu-jammy:/artifacts \
    -v $(pwd):/tmp/staging \
    docker.io/m0rf30/yap-ubuntu-jammy:1.8 \
    build ubuntu-jammy /tmp/staging/
elif [[ $OS == "rocky-8" ]]
then
  docker run -it --rm \
    --entrypoint=yap \
    -v $(pwd)/artifacts/rocky-8:/artifacts \
    -v $(pwd):/tmp/staging \
    docker.io/m0rf30/yap-rocky-8:1.10 \
    build rocky-8 /tmp/staging/
fi

rm package/config.properties
rm package/carbonio-files.jar
rm package/carbonio-files-handle-kv-changes.py
rm package/carbonio-files-start-watches.sh
rm package/carbonio-files-watches.service