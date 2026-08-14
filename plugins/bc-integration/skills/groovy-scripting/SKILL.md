---
description: Write or debug Groovy or JavaScript scripts used inside a Boomi Data Process shape (custom scripting step in an AtomSphere process). Use when the user needs a Boomi script, mentions ExecutionUtil/DataContext/dynamic process properties in scripting, or pastes a Boomi script that's erroring.
---

# Boomi Data Process scripting (Groovy / JavaScript)

Boomi's `Data Process` shape runs custom Groovy or JavaScript against the documents flowing through a process. Scripts operate on the shape's input document collection and must return a matching output collection.

## Groovy script skeleton

```groovy
import com.boomi.execution.ExecutionUtil
import java.io.InputStream
import java.io.OutputStream

// dataContext gives access to the documents entering the shape
for (int i = 0; i < dataContext.getDataCount(); i++) {
    InputStream is = dataContext.getStream(i)
    Properties props = dataContext.getProperties(i)

    byte[] data = is.getBytes() // read fully if you need the whole payload
    String content = new String(data, "UTF-8")

    // ... transform `content` ...

    ByteArrayOutputStream os = new ByteArrayOutputStream()
    os.write(content.getBytes("UTF-8"))
    dataContext.storeStream(new ByteArrayInputStream(os.toByteArray()), props)
}
```

Key APIs:
- `dataContext` — the implicit binding giving access to input/output documents (`getDataCount`, `getStream`, `getProperties`, `storeStream`).
- `ExecutionUtil.getBaseUserDir()` / `getDynamicProcessProperty(name)` / `setDynamicProcessProperty(name, value, persist)` — read/write DPPs from script.
- `props.getProperty("document.dynamic.userdefined.<DDP name>")` — read a Dynamic Document Property on the current document.
- `ExecutionUtil.getExecutionId()` — for correlating logs across an execution.
- Logging: `ExecutionUtil.getBaseLogger().info(...)` writes to the process execution log, visible in Process Reporting.

## JavaScript equivalent

```javascript
for (var i = 0; i < dataContext.getDataCount(); i++) {
    var is = dataContext.getStream(i);
    var props = dataContext.getProperties(i);
    // read is, transform, write to a new stream
    dataContext.storeStream(newStream, props);
}
```

## Common pitfalls

- **Not consuming or not re-storing every input document.** If a script reads N documents but only calls `storeStream` M < N times, downstream shapes silently receive fewer documents — always account for every index or explicitly drop/split with `dataContext.storeStream`/`storeStream` per intended output.
- **Reading a stream twice.** `InputStream` from `dataContext.getStream(i)` is not re-readable after consumption; buffer it (e.g. `is.getBytes()`) if you need to inspect it more than once.
- **Encoding assumptions.** Always specify the charset explicitly (`"UTF-8"`) when converting bytes/strings — Boomi Atoms can run on platforms with different default encodings.
- **Large payloads in memory.** Reading a whole large document into a `byte[]`/String works for typical EDI/JSON/XML documents but is risky for very large flat files; prefer streaming transforms (line-by-line) when documents can be large.
- **State leaking across documents.** Declare loop-scoped variables inside the per-document loop; a variable declared outside the loop and mutated inside it will leak values across documents in the same batch.
- **Script errors abort the batch.** An uncaught exception in the script shape typically fails the whole execution — wrap risky per-document logic in try/catch inside the script and route failed documents to an error property instead of throwing, unless a hard stop is actually desired.

When asked to write a script, first confirm: input profile/format, desired output format, whether it should process one document per invocation or the whole batch, and whether any DPP/DDP values need to be read or set.
