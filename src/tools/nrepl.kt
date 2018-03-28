/*
 * Copyright 2016-present Greg Shrago
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.intellij.clojure.nrepl

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.util.ConcurrencyUtil
import org.intellij.clojure.util.cast
import java.io.*
import java.net.Socket
import java.net.SocketException
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.reflect.KProperty

/**
 * @author gregsh
 */
private val LOG = Logger.getInstance(NReplClient::class.java)
val PING_TIMEOUT = 5_000L
val PING_DELAY = 30_000L

private object ClientID {
  private val id = AtomicLong(0)
  operator fun getValue(thisRef: Any?, property: KProperty<*>) = id.incrementAndGet()
}

class NReplClient {

  private var transport: Transport = NOT_CONNECTED
  val isConnected: Boolean get() = transport != NOT_CONNECTED
  fun ping() = isConnected && pingImpl()

  private fun pingImpl(session: String = mainSession) =
      try { eval("42") { this.session = session }.get(PING_TIMEOUT, TimeUnit.MILLISECONDS)["value"] == "42" }
      catch (e: Exception) { false }

  private val nextId: Long by ClientID
  private val callbacks = ConcurrentHashMap<Long, Request>()
  private val partialResponses = HashMap<Long, MutableMap<String, Any?>>()

  var mainSession = ""
    private set
  var toolSession = ""
    private set
  var defaultRequest: Request? = null

  fun connect(host: String, port: Int) {
    if (isConnected) throw IllegalStateException("Already connected")
    try {
      transport = AsyncTransport(SocketTransport(Socket(host, port))) { o -> runCallbacks(o) }
      mainSession = if (mainSession != "" && pingImpl(mainSession)) mainSession else createSession()
      toolSession = if (toolSession != "" && pingImpl(toolSession)) toolSession else createSession()
    }
    catch(e: Exception) {
      if (e is SocketException) LOG.warn("nrepl://$host:$port failed: $e")
      else LOG.warn("nrepl://$host:$port failed", e)
      if (transport != NOT_CONNECTED) disconnect()
      throw e
    }
  }

  fun disconnect() {
    try {
      defaultRequest = null
      try { closeSession(mainSession) } catch (e: Exception) { }
      try { closeSession(toolSession) } catch (e: Exception) { }
    }
    finally {
      val tmp = transport
      transport = NOT_CONNECTED
      val reason = (tmp as? AsyncTransport)?.closed?.get() ?: ProcessCanceledException()
      try {
        tmp.close()
      }
      catch (ignore: Throwable) { }
      finally {
        try {
          clearCallbacks(reason)
        }
        catch (ignore: Throwable) {
        }
      }
    }
  }

  inner class Request {
    constructor(op: String) { this.op = op }

    internal val map = HashMap<String, Any?>()
    internal val future = CompletableFuture<Map<String, Any?>>()
    var stdout: ((String) -> Unit)? = null
    var stderr: ((String) -> Unit)? = null
    var stdin: (((String) -> Unit) -> Unit)? = null

    var op: String? get() = get("op") as? String; set(op) { set("op", op) }
    var session: Any? get() = get("session"); set(op) { set("session", op) }
    var namespace: String? get() = get("ns") as String?; set(op) { set("ns", op) }
    var code: String? get() = get("code") as String?; set(op) { set("code", op) }

    operator fun get(prop: String) = map[prop]
    operator fun set(prop: String, value: Any?) { if (value == null) map -= prop else map[prop] = value }

    fun send() = send(this)
    fun sendAndReceive() = send().get()!!
  }

  private fun send(r: Request): CompletableFuture<Map<String, Any?>> {
    val id = nextId
    r["id"] = id
    callbacks[id] = r
    try {
      transport.send(r.map)
    }
    catch (ex: IOException) {
      try { transport.close() } catch (ignore: Throwable) {}
      transport = NOT_CONNECTED
      r.future.completeExceptionally(ex)
    }
    catch (ex: Throwable) {
      r.future.completeExceptionally(ex)
    }
    return r.future
  }

  private fun runCallbacks(o: Any?) {
    val m = o.cast<Map<String, Any?>>() ?: clearCallbacks(o as? Throwable ?: Throwable(o.toString())).run { return }
    val id = m["id"] as? Long ?: return
    val r = callbacks[id] ?: defaultRequest ?: return
    val status = m["status"].let { (it as? List<*>)?.firstOrNull() ?: it }
    r.stdout?.let { handler -> (m["out"] as? String)?.let { msg -> handler(msg) } }
    r.stderr?.let { handler -> (m["err"] as? String)?.let { msg -> handler(msg) } }
    if (status == "need-input") r.stdin?.let { handler ->
      handler { input ->
        request("stdin") {
          session = r.session
          stdin = r.stdin
          stdout = r.stdout
          set("stdin", input)
        }.send()
      }
    }
    val keyOp: (String) -> JoinOp = {
      when (it) {
        "id", "session", "root-ex" -> JoinOp.SKIP
        "status" -> JoinOp.OVERRIDE
        "out" -> if (r.stdout != null) JoinOp.SKIP else JoinOp.JOIN
        "err" -> if (r.stderr != null) JoinOp.SKIP else JoinOp.JOIN
        else -> JoinOp.JOIN
      }
    }
    if (status == "done") {
      val combined = (partialResponses.remove(id) ?: LinkedHashMap()).apply { joinMaps(m, keyOp) }
      callbacks.remove(id)?.future?.complete(combined)
    }
    else {
      partialResponses[id] = (partialResponses[id] ?: LinkedHashMap()).apply { joinMaps(m, keyOp) }
    }
  }

  private fun clearCallbacks(reason: Throwable) {
    val cb = HashMap(callbacks)
    callbacks.clear()
    for (value in cb.values) {
      value.future.completeExceptionally(reason)
    }
  }

  private enum class JoinOp { SKIP, OVERRIDE, JOIN }
  private fun MutableMap<String, Any?>.joinMaps(m: Map<String, Any?>, keyOp: (String) -> JoinOp = { JoinOp.JOIN }) {
    m.keys.forEach loop@ { key ->
      val val1 = get(key)
      val val2 = m[key]
      val op = keyOp(key)
      when {
        op == JoinOp.SKIP -> remove(key)
        val1 == null || op == JoinOp.OVERRIDE -> put(key, val2)
        val1 is ArrayList<*> -> val1.cast<ArrayList<Any?>>()!!.run { if (val2 is Collection<*>) addAll(val2) else add(val2) }
        val1 is MutableMap<*, *> && val2 is Map<*, *> -> val1.cast<MutableMap<String, Any?>>()!!.joinMaps(val2.cast()!!)
        key == "value" -> put(key, ArrayList(listOf(val1, val2)))
        key == "ns" -> put(key, val2)
        val1 is String && val2 is String -> put(key, val1 + val2)
      }
    }
  }

  fun createSession() = request("clone").sendAndReceive().let { it["new-session"] as String }
  fun closeSession(session: String, f: Request.() -> Unit = {}) = request("close") { this.session = session; f(this) }.send()
  fun describeSession(f: Request.() -> Unit = {}) = request("describe", f).send()
  fun eval(code: String? = null, f: Request.() -> Unit = {}) = request("eval") { this.code = code; f(this) }.send()

  fun request(op: String, f: Request.() -> Unit = {}): Request = Request(op).apply {
    f()
    if (session == null && mainSession != "") session = mainSession
  }
}

fun dumpObject(o: Any?) = StringBuilder().let { sb ->
  fun dump(o: Any?, off: String) {
    fun newLine(index: Int) = if (index > 0 || !off.isEmpty()) sb.append("\n").append(off) else sb
    when (o) {
      is Map<*, *> -> {
        o.keys.forEachIndexed { i, key -> newLine(i).append(key).append(": "); dump(o[key] as Any, off + "  ") }
      }
      is Collection<*> -> {
        o.forEachIndexed { i, it -> newLine(i); dump(it as Any, off + "  ") }
      }
      is ByteArray -> sb.append(String(o))
      else -> sb.append(o)
    }
  }
  dump(o, "")
  sb.toString()
}

abstract class Transport : Closeable {
  open fun recv(): Any? = recv(Long.MAX_VALUE)
  abstract fun recv(timeout: Long): Any?
  abstract fun send(message: Any)
  override fun close() = Unit
}

val NOT_CONNECTED = object : Transport() {
  override fun recv(timeout: Long) = throw IllegalStateException()
  override fun send(message: Any) = throw IllegalStateException()
}

class AsyncTransport(private val delegate: Transport, private val responseHandler: (Any?) -> Any) : Transport() {
  companion object {
    private val threadPool = Executors.newCachedThreadPool(ConcurrencyUtil.newNamedThreadFactory("clojure-kit-nrepl", true, Thread.NORM_PRIORITY))!!
  }

  val closed = AtomicReference<Throwable>()
  val reader = threadPool.submit {
    while (closed.get() == null) {
      val response = try {
        delegate.recv()
      }
      catch (ex: Throwable) {
        closed.set(ex)
        ex
      }
      try {
        responseHandler(response)
      }
      catch (ex: Throwable) {
        try {
          LOG.error(ex)
        }
        catch (ignore: Throwable) {}
      }
    }
  }!!

  override fun recv(timeout: Long) = throw IllegalStateException()

  override fun send(message: Any) = closed.get()?.let { throw it } ?: delegate.send(message)

  override fun close() {
    try {
      closed.compareAndSet(null, ProcessCanceledException())
      delegate.close()
    }
    catch(t: Throwable) {
      LOG.warn(t)
    }
    finally {
      reader.cancel(true)
    }
  }
}

class SocketTransport(val socket: Socket) : Transport() {
  val input = BEncodeInput(socket.inputStream)
  val output = BEncodeOutput(socket.outputStream)

  override fun recv(timeout: Long) = wrap { input.read() }
  override fun send(message: Any) = wrap { synchronized(output) { output.write(message); output.stream.flush() } }
  override fun close() = socket.close()

  fun <T> wrap(proc: () -> T): T = try {
    proc()
  }
  catch (e: EOFException) {
    throw SocketException("The transport's socket appears to have lost its connection to the nREPL server")
  }
  catch (e: Throwable) {
    throw if (!socket.isConnected)
      SocketException("The transport's socket appears to have lost its connection to the nREPL server")
    else e
  }
}

class BEncodeInput(stream: InputStream) {
  val stream = PushbackInputStream(stream)

  fun read(): Any? = read_ch().let { token ->
    when (token) {
      'e' -> null
      'i' -> read_long('e')
      'l' -> read_list()
      'd' -> read_map()
      else -> stream.unread(token.toInt()).let {
        val bytes = read_netstring_inner()
        try {
          String(bytes)
        }
        catch(e: Exception) {
          bytes
        }
      }
    }
  }

  fun read_netstring(): ByteArray {
    val result = read_netstring_inner()
    if (read_ch() != ',') throw IOException("Invalid netstring. ',' expected.")
    return result
  }

  fun read_list(): List<Any> {
    val result = ArrayList<Any>()
    while (true) {
      result.add(read() ?: return result)
    }
  }

  fun read_map(): Map<String, Any?> {
    val result = LinkedHashMap<String, Any?>()
    while (true) {
      result.put(read() as? String ?: return result, read())
    }
  }

  private fun read_long(delim: Char): Long {
    var result = 0L
    var negate = false
    while (true) {
      val b = read_ch()
      if (b == delim) return result
      if (b == '-' && result == 0L && !negate) negate = true
      else if (b >= '0' || b <= '9') result = result * 10 + (b - '0')
      else throw IOException("Invalid long. Unexpected $b encountered.")
    }
  }

  private fun read_netstring_inner(): ByteArray {
    return read_bytes(read_long(':').toInt())
  }

  private fun read_bytes(n: Int): ByteArray {
    val result = ByteArray(n)
    var offset = 0
    var left = n
    while (true) {
      val actual = stream.read(result, offset, left)
      if (actual < 0) throw EOFException("Invalid netstring. Less data available than expected.")
      else if (actual == left) return result
      else {
        offset += actual; left -= actual
      }
    }
  }

  private fun read_ch(): Char {
    val c = stream.read()
    if (c < 0) throw EOFException("Invalid netstring. Unexpected end of input.")
    return c.toChar()
  }
}

class BEncodeOutput(val _stream: OutputStream) {
  val bos = ByteArrayOutputStream()
  val stream = object : OutputStream() {
    override fun write(b: Int) {
      bos.write(b)
      _stream.write(b)
    }
  }

  fun write(o: Any): Unit = when (o) {
    is ByteArray -> write_netstring_inner(o)
    is InputStream -> write_netstring_inner(ByteArrayOutputStream().let { o.copyTo(it); it.toByteArray() })
    is Number -> write_long(o)
    is String -> write_netstring_inner(o.toByteArray(Charsets.UTF_8))
    is Map<*, *> -> write_map(o)
    is Iterable<*> -> write_list(o)
    is Array<*> -> write_list(o.asList())
    else -> throw IllegalArgumentException("Cannot write value of type ${o.javaClass.name}")
  }

  fun write_netstring(o: ByteArray) {
    write_netstring_inner(o)
    stream.write(','.toInt())
  }

  private fun write_long(o: Number) {
    stream.write('i'.toInt())
    stream.write(o.toString().toByteArray(Charsets.UTF_8))
    stream.write('e'.toInt())
  }

  private fun write_list(o: Iterable<*>) {
    stream.write('l'.toInt())
    o.forEach { write(it!!) }
    stream.write('e'.toInt())
  }

  private fun write_map(o: Map<*, *>) {
    stream.write('d'.toInt())
    val sorted = ArrayList<Pair<Any, ByteArray>>(o.size).apply {
      o.keys.forEach {
        add(Pair(it!!, it.toString().toByteArray(Charsets.UTF_8)))
      }
    }
    Collections.sort(sorted, { p1, p2 -> compare(p1.second, p2.second) })
    sorted.forEach { p -> write(p.second); write(o[p.first]!!) }
    stream.write('e'.toInt())
  }

  private fun write_netstring_inner(o: ByteArray) {
    stream.write(o.size.toString().toByteArray(Charsets.UTF_8))
    stream.write(':'.toInt())
    stream.write(o)
  }

  private fun compare(b1: ByteArray, b2: ByteArray): Int {
    for (i in 0..Math.min(b1.size, b2.size) - 1) {
      (b1[i] - b2[i]).let { if (it != 0) return it }
    }
    return b1.size - b2.size
  }
}