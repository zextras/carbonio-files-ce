const express = require('express');
const fs = require('fs').promises;
const path = require('path');
const crypto = require('crypto');

const app = express();
const port = 10000;

const STORAGE_DIR = '/storage';

app.use((req, res, next) => {
  if (req.url.includes('/upload')) {
    next();
  } else {
    express.json()(req, res, next);
  }
});

async function ensureStorageDir() {
  try {
    await fs.mkdir(STORAGE_DIR, { recursive: true });
  } catch (err) {
    console.error('Error creating storage directory:', err);
  }
}

function getFilePath(node, version, type) {
  const versionStr = version ? `_v${version}` : '';
  return path.join(STORAGE_DIR, type, `${node}${versionStr}`);
}

function calculateDigest(buffer) {
  return crypto.createHash('sha256').update(buffer).digest('hex');
}

app.delete('/delete', async (req, res) => {
  const { node, version, type } = req.query;

  try {
    const filePath = getFilePath(node, version, type);
    await fs.unlink(filePath);
    res.status(204).send();
  } catch (err) {
    if (err.code === 'ENOENT') {
      res.status(404).json({ error: 'File not found' });
    } else {
      res.status(500).json({ error: 'Internal server error' });
    }
  }
});

app.get('/download', async (req, res) => {
  const { node, version, type } = req.query;

  try {
    const filePath = getFilePath(node, version, type);
    const data = await fs.readFile(filePath);
    res.set('Content-Type', 'application/octet-stream');
    res.send(data);
  } catch (err) {
    if (err.code === 'ENOENT') {
      res.status(404).json({ error: 'File not found' });
    } else {
      res.status(500).json({ error: 'Internal server error' });
    }
  }
});

async function extractFileFromMultipart(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];

    req.on('data', chunk => {
      chunks.push(chunk);
    });

    req.on('end', () => {
      const buffer = Buffer.concat(chunks);
      const contentType = req.headers['content-type'];
      const boundary = '--' + contentType.split('boundary=')[1];

      const boundaryBuffer = Buffer.from(boundary);
      const startIndex = buffer.indexOf(boundaryBuffer);

      if (startIndex === -1) {
        reject(new Error('Boundary not found'));
        return;
      }

      const headerEnd = buffer.indexOf('\r\n\r\n', startIndex);
      if (headerEnd === -1) {
        reject(new Error('Headers end not found'));
        return;
      }

      const contentStart = headerEnd + 4;

      const endBoundary = buffer.indexOf(boundaryBuffer, contentStart);
      if (endBoundary === -1) {
        reject(new Error('End boundary not found'));
        return;
      }

      const fileContent = buffer.slice(contentStart, endBoundary - 2);

      console.log(`File extracted: ${fileContent.length} bytes`);
      resolve(fileContent);
    });

    req.on('error', reject);
  });
}

async function handleUpload(req, res, overwrite) {
  const { node, version, type } = req.query;

  console.log(`Processing upload for node=${node}, version=${version}, type=${type}`);

  try {
    const fileBuffer = await extractFileFromMultipart(req);

    const filePath = getFilePath(node, version, type);
    const dir = path.dirname(filePath);

    await fs.mkdir(dir, { recursive: true });

    if (!overwrite) {
      try {
        await fs.access(filePath);
        return res.status(409).json({ error: 'File already exists' });
      } catch (err) {
      }
    }

    await fs.writeFile(filePath, fileBuffer);
    console.log(`File saved to ${filePath}`);

    const digest = calculateDigest(fileBuffer);

    const response = {
      query: {
        node: node,
        version: version ? parseInt(version) : null,
        type: type
      },
      resource: filePath,
      digest: digest,
      digest_algorithm: 'SHA-256',
      size: fileBuffer.length
    };

    console.log('Upload successful, sending response');
    res.json(response);
  } catch (err) {
    console.error('Error in handleUpload:', err);
    res.status(500).json({ error: 'Internal server error: ' + err.message });
  }
}

app.put('/upload', (req, res) => {
  handleUpload(req, res, true);
});

app.post('/upload', (req, res) => {
  handleUpload(req, res, false);
});

app.put('/copy', async (req, res) => {
  const { sourceNode, sourceVersion, destinationNode, destinationVersion, type, override } = req.query;

  try {
    const sourcePath = getFilePath(sourceNode, sourceVersion, type);
    const destPath = getFilePath(destinationNode, destinationVersion, type);

    const sourceData = await fs.readFile(sourcePath);

    const destDir = path.dirname(destPath);
    await fs.mkdir(destDir, { recursive: true });

    if (override === 'false') {
      try {
        await fs.access(destPath);
        return res.status(409).json({ error: 'Destination file already exists' });
      } catch (err) {
      }
    }

    await fs.writeFile(destPath, sourceData);

    const digest = calculateDigest(sourceData);

    const response = {
      query: {
        node: destinationNode,
        version: destinationVersion ? parseInt(destinationVersion) : null,
        type: type
      },
      resource: destPath,
      digest: digest,
      digest_algorithm: 'SHA-256',
      size: sourceData.length
    };

    res.json(response);
  } catch (err) {
    if (err.code === 'ENOENT') {
      res.status(404).json({ error: 'Source file not found' });
    } else {
      res.status(500).json({ error: 'Internal server error' });
    }
  }
});

app.post('/bulk-delete', express.json(), async (req, res) => {
  const { type } = req.query;
  const { ids } = req.body;

  if (!ids || !Array.isArray(ids)) {
    return res.status(400).json({ error: 'Invalid request body' });
  }

  const results = [];

  for (const item of ids) {
    const filePath = getFilePath(item.node, item.version, type);
    try {
      await fs.unlink(filePath);
      results.push({
        node: item.node,
        version: item.version,
        type: type,
        success: true
      });
    } catch (err) {
      results.push({
        node: item.node,
        version: item.version,
        type: type,
        success: false,
        error: err.code === 'ENOENT' ? 'File not found' : 'Delete failed'
      });
    }
  }

  res.json({ ids: results });
});

app.get('/health/live', (req, res) => {
  res.status(200).send();
});

ensureStorageDir().then(() => {
  app.listen(port, '0.0.0.0', () => {
    console.log(`Storages mock service listening on port ${port}`);
  });
});