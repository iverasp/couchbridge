# couchbridge

Read-only REST API that serves an Obsidian vault folder (default `recipes/`) straight from the CouchDB database used by
[Obsidian LiveSync](https://github.com/vrtmrz/obsidian-livesync). YAML frontmatter is returned as JSON.

Written in Kotlin/Native (Ktor). Targets: `linuxX64` (Docker) and `macosArm64`.

## API

`/contents` paths are relative to `ROOT_FOLDER`. `/raw` takes a full vault path, so attachments stored outside the root
folder (e.g. `attachments/pie.png`) can still be served. Use the `url` / `rawUrl` fields in responses rather than
building paths yourself.

| Endpoint | Description |
| --- | --- |
| `GET /contents` | Root folder listing |
| `GET /contents/{path}` | Folder listing, document or file info. Check the `type` field: `folder`, `document` or `file` |
| `GET /raw/{vaultPath}` | Raw file bytes with the correct `Content-Type` (images, original markdown) |
| `GET /health` | `200` once the initial sync is done, `503` while syncing |

Add `?metadata=true` to a folder listing to include the frontmatter of each document.

```jsonc
// GET /contents/Desserts?metadata=true
{
  "type": "folder",
  "name": "Desserts",
  "path": "Desserts",
  "parent": "",
  "items": [
    { "type": "folder", "name": "Cakes", "path": "Desserts/Cakes", "url": "/contents/Desserts/Cakes" },
    {
      "type": "document", "name": "Apple Pie.md", "path": "Desserts/Apple Pie.md",
      "url": "/contents/Desserts/Apple%20Pie.md", "rawUrl": "/raw/recipes/Desserts/Apple%20Pie.md",
      "size": 120, "ctime": 1, "mtime": 2,
      "metadata": { "title": "Apple Pie", "servings": 8, "tags": ["dessert", "baking"] }
    },
    {
      "type": "file", "name": "cake.png", "path": "Desserts/cake.png",
      "url": "/contents/Desserts/cake.png", "rawUrl": "/raw/recipes/Desserts/cake.png", "size": 31
    }
  ]
}

// GET /contents/Desserts/Apple%20Pie.md
{
  "type": "document",
  "name": "Apple Pie.md",
  "path": "Desserts/Apple Pie.md",
  "rawUrl": "/raw/recipes/Desserts/Apple%20Pie.md",
  "metadata": { "title": "Apple Pie", "servings": 8, "tags": ["dessert", "baking"], "image": "[[pie.png]]" },
  "body": "# Apple Pie\n![[pie.png|200]]\n",
  "attachments": [
    { "link": "pie.png", "path": "attachments/pie.png", "url": "/raw/attachments/pie.png", "contentType": "image/png" }
  ]
}

// GET /contents/Desserts/cake.png
{ "type": "file", "name": "cake.png", "path": "Desserts/cake.png", "rawUrl": "/raw/recipes/Desserts/cake.png", "contentType": "image/png", "size": 31 }
```

Documents also get `ingredients` and `instructions`: the markdown under the `## Ingredienser` and `## Instruksjoner`
headings (up to the next heading of level 1–2), with blank lines at the start and end removed. They are left out when
the heading is missing. `body` still contains the full markdown.

Attachments are found from `![[embeds]]`, `![markdown](images)` and `[[links]]` in frontmatter values, and resolved
like Obsidian does (exact path, relative to the note, then shortest matching file name in the vault). Unresolved
links are listed without `url`.

Errors are JSON `{"error": "..."}` with status `404` when a path doesn't exist.

## Configuration

| Variable | Default |
| --- | --- |
| `COUCHDB_URL` | `http://couchdb:5984` |
| `COUCHDB_USER` / `COUCHDB_PASSWORD` | – |
| `COUCHDB_DATABASE` | required |
| `ROOT_FOLDER` | `recipes` |
| `HOST` / `PORT` | `0.0.0.0` / `8080` |

LiveSync end-to-end encryption and path obfuscation must be off.

`COUCHDB_URL` can be `http://` or `https://`. HTTPS uses the system CA certificates, so the CouchDB server needs a
certificate from a trusted CA.

## Running

Docker Compose (on the same network as CouchDB):

```sh
cp .env.example .env   # fill in credentials
docker compose up -d --build
```

Natively on macOS (Apple Silicon):

```sh
./gradlew linkReleaseExecutableMacosArm64
COUCHDB_URL=http://localhost:5984 COUCHDB_USER=admin COUCHDB_PASSWORD=... COUCHDB_DATABASE=obsidian \
  ./build/bin/macosArm64/releaseExecutable/couchbridge.kexe
```

Tests: `./gradlew macosArm64Test`

## How it works

LiveSync stores each file as a metadata document (`type: plain` for text, `newnote` for binary) whose `children` are
chunk document ids (`h:...`). On startup the service reads all metadata documents through the `_changes` feed and
keeps the index up to date with a long-poll on the same feed. File content is assembled from chunks on request;
parsed notes are cached per revision.
