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
import io.netty.util.concurrent.DefaultThreadFactory
import java.io.*
import java.net.Socket
import java.net.SocketException
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.reflect.KProperty

/**
 * @author gregsh
 */
private val LOG = Logger.getInstance(NReplClient::class.java)

private object clientID {
  private val id = AtomicLong(0)
  operator fun getValue(thisRef: Any?, property: KProperty<*>) = id.incrementAndGet()
}

class NReplClient(val host: String, val port: Int) {

  private var transport: Transport = BAD_TRANSPORT

  private val nextId: Long by clientID
  private val callbacks = ConcurrentHashMap<Long, CompletableFuture<Map<String, Any?>>>()
  private val partialResponses = HashMap<Long, MutableMap<String, Any?>>()

  lateinit private var mainSession: String
  lateinit private var toolingSession: String

  fun connect() {
    try {
      transport = AsyncTransport(SocketTransport(Socket(host, port))) { o -> runCallbacks(o) }
      mainSession = createSession()
      toolingSession = createSession()
    }
    catch(e: Exception) {
      val t = transport
      transport = BAD_TRANSPORT
      LOG.warn(e)
      if (t != BAD_TRANSPORT) t.close()
      throw e
    }
  }

  val valid: Boolean get() = host != "" && port != -1 && transport != BAD_TRANSPORT

  private fun request(vararg m: kotlin.Pair<String, Any>) = request(mutableMapOf(*m))
  private fun request(m: MutableMap<String, Any>) = requestAsync(m).get()!!

  private fun requestAsync(vararg m: kotlin.Pair<String, Any>) = requestAsync(mutableMapOf(*m))
  private fun requestAsync(m: MutableMap<String, Any>): CompletableFuture<Map<String, Any?>> {
    val id = nextId
    val future = CompletableFuture<Map<String, Any?>>()
    callbacks.put(id, future)
    m.put("id", id)
    transport.send(m)
    return future
  }

  private fun runCallbacks(o: Any): Unit {
    val m = o as? Map<String, Any?> ?: return
    val id = m["id"] as? Long ?: return
    val combined = if (m["status"].let { it is List<*> && it.firstOrNull() == "done" }) {
      (partialResponses.remove(id) ?: HashMap()).apply { join(m) }
    }
    else {
      partialResponses[id] = (partialResponses[id] ?: LinkedHashMap()).apply { join(m) }
      return
    }
    try {
      callbacks.remove(id)?.complete(combined)
    }
    catch(e: Exception) {
      LOG.warn(e)
    }
  }

  private fun MutableMap<String, Any?>.join(m: Map<String, Any?>) {
    m.keys.forEach loop@ { key ->
      if (key == "id" || key == "session" || key == "root-ex") return@loop
      val val1 = get(key)
      val val2 = m[key]
      when {
        val1 == null -> put(key, val2)
        val1 is ArrayList<*> -> (val1 as ArrayList<Any?>).run { if (val2 is Collection<*>) addAll(val2) else add(val2) }
        val1 is MutableMap<*, *> && val2 is Map<*, *> -> (val1 as MutableMap<String, Any?>).join(val2 as Map<String, Any?>)
        key == "value" -> put(key, ArrayList(listOf(val1, val2)))
        key == "ns" -> put(key, val2)
      }
    }
  }

  fun disconnect() = transport.close()

  fun createSession() = request("op" to "clone").let { it["new-session"] as String }
  fun closeSession(session: String) = request("op" to "close", "session" to session)
  fun describeSession(session: String = mainSession) = request("op" to "describe", "session" to session)

  fun evalAsync(code: String, session: Any = mainSession) = requestAsync("op" to "eval", "session" to session, "code" to code)
}

fun dumpObject(o: Any?) = StringBuilder().let { sb ->
  fun dump(o: Any?, off: String) {
    when (o) {
      is Map<*, *> -> {
        o.keys.forEach { key -> sb.append("\n$off ${key as String}: "); dump(o[key] as Any, off + "  ") }
      }
      is Collection<*> -> {
        o.forEachIndexed { i, it -> sb.append(if (i > 0) "\n" else ""); dump(it as Any, off + "  ") }
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
  abstract fun send(message: Any): Unit
  override fun close() = Unit
}

val BAD_TRANSPORT = object : Transport() {
  override fun recv(timeout: Long) = throw IllegalStateException()
  override fun send(message: Any) = throw IllegalStateException()
}

class AsyncTransport(private val delegate: Transport, private val responseHandler: (Any) -> Any) : Transport() {
  companion object {
    private val threadPool = Executors.newCachedThreadPool(DefaultThreadFactory("clojure-kit-nrepl", true))!!
  }

  val closed = AtomicReference<Throwable>()
  val reader = threadPool.submit {
    while (closed.get() == null) {
      val response = try {
        delegate.recv()
      }
      catch (t: Throwable) {
        closed.set(t); t
      } ?: continue
      try {
        responseHandler(response)
      }
      catch (t: Throwable) {
        closed.set(t)
        LOG.error(t)
      }
    }
  }!!

  override fun recv(timeout: Long) = throw IllegalStateException()

  override fun send(message: Any) = closed.get()?.let { throw it } ?: delegate.send(message)

  override fun close() {
    try {
      closed.compareAndSet(null, Throwable("closed"))
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
          if (bytes.size < 1024) String(bytes) else bytes
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
      else if (b >= '0' || b <= '9') result = result * 10 + (b - '0').toInt()
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
    for (i in 0..Math.min(b1.size, b2.size)) {
      (b1[i] - b2[i]).let { if (it != 0) return it }
    }
    return b1.size - b2.size
  }
}