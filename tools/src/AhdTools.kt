// SPDX-License-Identifier: Apache-2.0
package io.github.ahdcamera.tools

import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Element

private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

fun main(args: Array<String>) {
    require(args.firstOrNull() == "manifest") { "Commands: manifest [--debug] source destination" }
    manifest(args.drop(1))
}

private fun manifest(arguments: List<String>) {
    val debug = arguments.firstOrNull() == "--debug"
    val paths = if (debug) arguments.drop(1) else arguments
    require(paths.size == 2) { "manifest [--debug] source destination" }
    val factory =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        }
    val parser = factory.newDocumentBuilder()
    val document = parser.parse(File(paths[0]))
    val root = document.documentElement
    root.setAttribute("package", "io.github.ahdcamera" + if (debug) ".debug" else "")
    root.setAttributeNS(ANDROID_NS, "android:versionCode", "1")
    root.setAttributeNS(
        ANDROID_NS,
        "android:versionName",
        "0.2.0" + if (debug) "-debug" else "",
    )
    val application = root.getElementsByTagName("application").item(0) as Element
    application.setAttributeNS(ANDROID_NS, "android:debuggable", debug.toString())
    TransformerFactory.newInstance()
        .apply {
            setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "")
        }
        .newTransformer()
        .apply { setOutputProperty(OutputKeys.INDENT, "yes") }
        .transform(DOMSource(document), StreamResult(File(paths[1])))
}
