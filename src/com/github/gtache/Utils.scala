package com.github.gtache

import java.io.File
import java.net.{MalformedURLException, URL}

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.{Editor, LogicalPosition}
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.vfs.VirtualFile
import org.eclipse.lsp4j.{Position, TextDocumentIdentifier}

/**
  * Object containing some useful methods for the plugin
  */
object Utils {

  private val LOG: Logger = Logger.getInstance(Utils.getClass)

  /**
    * Transforms an editor (Document) identifier to an LSP identifier
    *
    * @param editor The editor
    * @return The TextDocumentIdentifier
    */
  def editorToLSPIdentifier(editor: Editor): TextDocumentIdentifier = {
    new TextDocumentIdentifier(editorToURIString(editor))
  }

  /**
    * Returns the URI string corresponding to an Editor (Document)
    *
    * @param editor The Editor
    * @return The URI
    */
  def editorToURIString(editor: Editor): String = {
    new URL(FileDocumentManager.getInstance().getFile(editor.getDocument).getUrl).toURI.toString
  }

  /**
    * Returns the URI string corresponding to a VirtualFileSystem file
    *
    * @param file The file
    * @return the URI
    */
  def VFSToURIString(file: VirtualFile): String = {
    try {
      new URL(file.getUrl).toURI.toString
    } catch {
      case e: MalformedURLException =>
        LOG.warn(e)
        null
    }
  }

  /**
    * Returns the project path given an editor
    *
    * @param editor The editor
    * @return The project whose belongs the editor
    */
  def editorToProjectFolderPath(editor: Editor): String = {
    new File(editor.getProject.getBaseDir.getPath).getAbsolutePath
  }

  /**
    * Calculates a Position given an editor and an offset
    *
    * @param editor The editor
    * @param offset The offset
    * @return an LSP position
    */
  def offsetToLSPPos(editor: Editor, offset: Int): Position = {
    logicalToLSPPos(editor.offsetToLogicalPosition(offset))
  }

  /**
    * Transforms a LogicalPosition (IntelliJ) to an LSP Position
    *
    * @param position the LogicalPosition
    * @return the Position
    */
  def logicalToLSPPos(position: LogicalPosition): Position = {
    new Position(position.line, position.column)
  }

  /**
    * Transforms an LSP position to an editor offset
    *
    * @param editor The editor
    * @param pos    The LSPPos
    * @return The offset
    */
  def LSPPosToOffset(editor: Editor, pos: Position): Int = {
    editor.logicalPositionToOffset(LSPToLogicalPos(pos))
  }

  /**
    * Transforms an LSP position to a LogicalPosition
    *
    * @param position The LSPPos
    * @return The LogicalPos
    */
  def LSPToLogicalPos(position: Position): LogicalPosition = {
    new LogicalPosition(position.getLine, position.getCharacter)
  }

  /**
    * Returns a file type given an editor
    *
    * @param editor The editor
    * @return The FileType
    */
  def fileTypeFromEditor(editor: Editor): FileType = {
    FileDocumentManager.getInstance().getFile(editor.getDocument).getFileType
  }

  def arrayToString(arr: Array[Any], sep: String = ""): String = {
    arr.mkString(sep)
  }

  def serverDefinitionExtensionPointMapToArrayMap(map: java.util.Map[String, ServerDefinitionExtensionPoint]): java.util.Map[String, Array[String]] = {
    import scala.collection.JavaConverters._
    map.asScala.map(e => (e._1, serverDefinitionExtensionPointToArray(e._2))).asJava
  }

  def serverDefinitionExtensionPointToArray(serverDefinitionExtensionPoint: ServerDefinitionExtensionPoint): Array[String] = {
    Array(serverDefinitionExtensionPoint.ext, serverDefinitionExtensionPoint.packge, serverDefinitionExtensionPoint.mainClass) ++ serverDefinitionExtensionPoint.args
  }

  def arrayMapToServerDefinitionExtensionPointMap(map: java.util.Map[String, Array[String]]): java.util.Map[String, ServerDefinitionExtensionPoint] = {
    import scala.collection.JavaConverters._
    map.asScala.map(e => (e._1, arrayToServerDefinitionExtensionPoint(e._2))).asJava
  }

  def arrayToServerDefinitionExtensionPoint(arr: Array[String]): ServerDefinitionExtensionPoint = {
    ServerDefinitionExtensionPoint(arr.head, arr.tail.head, arr.tail.tail.head, arr.tail.tail.tail)
  }

}