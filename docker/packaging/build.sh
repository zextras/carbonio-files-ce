# SPDX-FileCopyrightText: 2025 Zextras <https://www.zextras.com>
#
# SPDX-License-Identifier: AGPL-3.0-only

#!/bin/bash

set -e

OUTPUT_DIR="artifacts"

echo "Building Docker image..."
IMAGE_ID=$(docker build -q -f Dockerfile ../../ 2>/dev/null)

echo "Starting container..."
CONTAINER_ID=$(docker run -d "${IMAGE_ID}" 2>/dev/null)

echo "Waiting for build to complete..."
docker wait "${CONTAINER_ID}" >/dev/null

echo "Creating output directory..."
mkdir -p "${OUTPUT_DIR}"

echo "Copying artifacts..."
docker cp "${CONTAINER_ID}:/output/." "${OUTPUT_DIR}/" 2>/dev/null

echo "Cleaning up container..."
docker rm "${CONTAINER_ID}" >/dev/null

echo "Cleaning up image..."
docker rmi "${IMAGE_ID}" >/dev/null

echo "Build completed successfully. Artifacts saved to ${OUTPUT_DIR}/"