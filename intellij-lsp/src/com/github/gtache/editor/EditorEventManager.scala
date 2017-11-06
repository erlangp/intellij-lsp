package com.github.gtache.editor

import java.awt.event.KeyEvent
import java.awt.{Color, Font, KeyboardFocusManager, Point}
import java.util.concurrent.{TimeUnit, TimeoutException}
import java.util.{Collections, Timer, TimerTask}

import com.github.gtache.client.languageserver.ServerOptions
import com.github.gtache.client.languageserver.requestmanager.RequestManager
import com.github.gtache.client.languageserver.wrapper.LanguageServerWrapperImpl
import com.github.gtache.contributors.psi.LSPPsiElement
import com.github.gtache.requests.{HoverHandler, WorkspaceEditHandler}
import com.github.gtache.utils.Utils
import com.intellij.codeInsight.lookup.{AutoCompletionPolicy, LookupElement, LookupElementBuilder}
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.colors.{EditorColors, TextAttributesKey}
import com.intellij.openapi.editor.event._
import com.intellij.openapi.editor.markup._
import com.intellij.openapi.editor.{Editor, LogicalPosition}
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ui.popup.{Balloon, JBPopupFactory, JBPopupListener, LightweightWindowEvent}
import com.intellij.openapi.util.{Computable, TextRange}
import com.intellij.psi.PsiReference
import com.intellij.ui.awt.RelativePoint
import org.eclipse.lsp4j._

import scala.collection.JavaConverters._
import scala.collection.mutable

object EditorEventManager {
  private val HOVER_TIME_THRES: Long = 1500000000L //1.5 sec
  private val SCHEDULE_THRES = 10000000 //Time before the Timer is scheduled
  private val POPUP_THRES = HOVER_TIME_THRES / 1000000 + 20

  private val uriToManager: mutable.Map[String, EditorEventManager] = mutable.HashMap()
  private val editorToManager: mutable.Map[Editor, EditorEventManager] = mutable.HashMap()

  @volatile private var isKeyPressed = false

  KeyboardFocusManager.getCurrentKeyboardFocusManager.addKeyEventDispatcher((e: KeyEvent) => this.synchronized {
    e.getID match {
      case KeyEvent.KEY_PRESSED => isKeyPressed = true
      case KeyEvent.KEY_RELEASED => isKeyPressed = false
      case _ =>
    }
    false
  })

  /**
    * @param uri A file uri
    * @return The manager for the given uri, or None
    */
  def forUri(uri: String): Option[EditorEventManager] = {
    uriToManager.get(uri)
  }

  /**
    * @param editor An editor
    * @return The manager for the given editor, or None
    */
  def forEditor(editor: Editor): Option[EditorEventManager] = {
    editorToManager.get(editor)
  }

  /**
    * Tells the server that all the documents will be saved
    */
  def willSaveAll(): Unit = {
    editorToManager.foreach(e => e._2.willSave())
  }
}

/**
  * Class handling events related to an Editor (a Document)
  *
  * @param editor              The "watched" editor
  * @param mouseListener       A listener for mouse clicks
  * @param mouseMotionListener A listener for mouse movement
  * @param documentListener    A listener for keystrokes
  * @param selectionListener   A listener for selection changes in the editor
  * @param requestManager      The related RequestManager, connected to the right LanguageServer
  * @param serverOptions       the options of the server regarding completion, signatureHelp, syncKind, etc
  * @param wrapper             The corresponding LanguageServerWrapper
  */
class EditorEventManager(val editor: Editor, val mouseListener: EditorMouseListener, val mouseMotionListener: EditorMouseMotionListener, val documentListener: DocumentListener, val selectionListener: SelectionListener, val requestManager: RequestManager, val serverOptions: ServerOptions, val wrapper: LanguageServerWrapperImpl) {


  import com.github.gtache.editor.EditorEventManager._
  import com.github.gtache.requests.Timeout._

  private val identifier: TextDocumentIdentifier = new TextDocumentIdentifier(Utils.editorToURIString(editor))
  private val LOG: Logger = Logger.getInstance(classOf[EditorEventManager])
  private val hoverThread = new Timer("Hover", true)
  private val changesParams = new DidChangeTextDocumentParams(new VersionedTextDocumentIdentifier(), Collections.singletonList(new TextDocumentContentChangeEvent()))
  private val selectedSymbHighlights: mutable.Set[RangeHighlighter] = mutable.HashSet()
  private val diagnosticsHighlights: mutable.Set[DiagnosticRangeHighlighter] = mutable.HashSet()
  private val syncKind = serverOptions.syncKind
  private val completionTriggers = if (serverOptions.completionOptions != null) serverOptions.completionOptions.getTriggerCharacters.asScala.toSet else Set[String]()
  private val signatureTriggers = if (serverOptions.signatureHelpOptions != null) serverOptions.signatureHelpOptions.getTriggerCharacters.asScala.toSet else Set[String]()
  private var version: Int = -1
  private var predTime: Long = -1L
  private var isOpen: Boolean = true
  @volatile private var isPopupOpen: Boolean = false
  private var mouseInEditor: Boolean = true
  @volatile private var currentPopup: Balloon = _

  uriToManager.put(Utils.editorToURIString(editor), this)
  editorToManager.put(editor, this)
  changesParams.getTextDocument.setUri(identifier.getUri)
  requestManager.didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(identifier.getUri, wrapper.serverDefinition.id, {
    version += 1
    version
  }, editor.getDocument.getText)))

  /**
    * Adds all the listeners
    */
  def addListeners(): Unit = {
    editor.addEditorMouseListener(mouseListener)
    editor.addEditorMouseMotionListener(mouseMotionListener)
    editor.getDocument.addDocumentListener(documentListener)
    editor.getSelectionModel.addSelectionListener(selectionListener)
  }

  /**
    * Removes all the listeners
    */
  def removeListeners(): Unit = {
    editor.removeEditorMouseMotionListener(mouseMotionListener)
    editor.getDocument.removeDocumentListener(documentListener)
    editor.removeEditorMouseListener(mouseListener)
    editor.getSelectionModel.removeSelectionListener(selectionListener)
  }

  /**
    * Tells the manager that the mouse is in the editor
    */
  def startListening(): Unit = {
    mouseInEditor = true
  }

  /**
    * Tells the manager that the mouse is not in the editor
    */
  def stopListening(): Unit = {
    mouseInEditor = false
  }

  /**
    * Manages the change of selected text in the editor
    *
    * @param e The selection event
    */
  def selectionChanged(e: SelectionEvent): Unit = {
    if (e.getEditor == editor) {
      selectedSymbHighlights.foreach(h => editor.getMarkupModel.removeHighlighter(h))
      selectedSymbHighlights.clear()
      if (editor.getSelectionModel.hasSelection) {
        val ideRange = e.getNewRange
        val LSPPos = Utils.offsetToLSPPos(editor, ideRange.getStartOffset)
        val request = requestManager.documentHighlight(new TextDocumentPositionParams(identifier, LSPPos))
        if (request != null) {
          ApplicationManager.getApplication.executeOnPooledThread(new Runnable {
            override def run(): Unit = {
              import scala.collection.JavaConverters._
              try {
                val resp = request.get(DOC_HIGHLIGHT_TIMEOUT, TimeUnit.MILLISECONDS).asScala
                ApplicationManager.getApplication.invokeLater(() => resp.foreach(dh => {
                  val range = dh.getRange
                  val kind = dh.getKind
                  val startOffset = Utils.LSPPosToOffset(editor, range.getStart)
                  val endOffset = Utils.LSPPosToOffset(editor, range.getEnd)
                  val colorScheme = editor.getColorsScheme
                  val highlight = editor.getMarkupModel.addRangeHighlighter(startOffset, endOffset, HighlighterLayer.SELECTION-1, colorScheme.getAttributes(EditorColors.IDENTIFIER_UNDER_CARET_ATTRIBUTES), HighlighterTargetArea.EXACT_RANGE)
                  selectedSymbHighlights.add(highlight)
                }))
              } catch {
                case e: TimeoutException =>
                  LOG.warn(e)
              }
            }
          })
        }
      }
    }
  }


  /**
    * Will show documentation if the mouse doesn't move for a given time (Hover)
    *
    * @param e the event
    */
  def mouseMoved(e: EditorMouseEvent): Unit = {
    if (e.getEditor == editor) {
      val curTime = System.nanoTime()
      if (predTime == (-1L)) {
        predTime = curTime
      } else {
        if (!isPopupOpen && !isKeyPressed) {
          scheduleDocumentation(curTime, getPos(e), e.getMouseEvent.getPoint)
        } else if (currentPopup != null) {
          currentPopup.hide()
          currentPopup = null
        }
        predTime = curTime
      }
    } else {
      LOG.error("Wrong editor for EditorEventManager")
    }
  }

  private def getPos(e: EditorMouseEvent): LogicalPosition = {
    val mousePos = e.getMouseEvent.getPoint
    val editorPos = editor.xyToLogicalPosition(mousePos)
    val doc = e.getEditor.getDocument
    val maxLines = doc.getLineCount
    if (editorPos.line >= maxLines) {
      null
    } else {
      val minY = doc.getLineStartOffset(editorPos.line) - (if (editorPos.line > 0) doc.getLineEndOffset(editorPos.line - 1) else 0)
      val maxY = doc.getLineEndOffset(editorPos.line) - (if (editorPos.line > 0) doc.getLineEndOffset(editorPos.line - 1) else 0)
      if (editorPos.column < minY || editorPos.column > maxY) {
        null
      } else {
        editorPos
      }
    }
  }

  private def scheduleDocumentation(time: Long, editorPos: LogicalPosition, point: Point): Unit = {
    if (editorPos != null) {
      if (time - predTime > SCHEDULE_THRES) {
        hoverThread.schedule(new TimerTask {
          override def run(): Unit = {
            val curTime = System.nanoTime()
            if (curTime - predTime > HOVER_TIME_THRES && mouseInEditor && editor.getContentComponent.hasFocus && !isPopupOpen && !isKeyPressed) {
              val editorOffset = ApplicationManager.getApplication.runReadAction(new Computable[Int] {
                override def compute(): Int = editor.logicalPositionToOffset(editorPos)
              })
              val inHighlights = diagnosticsHighlights.filter(diag => diag.rangeHighlighter.getStartOffset <= editorOffset && editorOffset <= diag.rangeHighlighter.getEndOffset).toList.sortBy(diag => diag.rangeHighlighter.getLayer)
              if (inHighlights.nonEmpty) {
                val first = inHighlights.head
                val message = first.message
                val code = first.code
                val source = first.source
                createAndShowBalloon(if (source != "" && source != null) source + " : " + message else message, time, point)
              } else {
                requestAndShowDoc(curTime, editorPos, point)
              }
            }
          }
        }, POPUP_THRES)
      }
    }
  }

  private def requestAndShowDoc(curTime: Long, editorPos: LogicalPosition, point: Point): Unit = {
    isPopupOpen = true
    val serverPos = Utils.logicalToLSPPos(editorPos)
    val request = requestManager.hover(new TextDocumentPositionParams(identifier, serverPos))
    if (request != null) {
      try {
        val hover = request.get(HOVER_TIMEOUT, TimeUnit.MILLISECONDS)
        val range = hover.getRange
        val string = HoverHandler.getHoverString(hover)
        if (string != null && string!="") {
          createAndShowBalloon(string, curTime, point)
        } else {
          isPopupOpen = false
          LOG.warn("Hover string returned is null for file " + identifier.getUri + " and pos (" + serverPos.getLine + ";" + serverPos.getCharacter + ")")
        }
      } catch {
        case e: TimeoutException =>
          isPopupOpen = false
          LOG.warn(e)
      }
    }


  }

  //TODO Use QuickDocInfoPane, HintManagerImpl, LightweightHint
  private def createAndShowBalloon(string: String, curTime: Long, point: Point): Unit = {
    ApplicationManager.getApplication.invokeLater(() => {
      val popupBuilder = JBPopupFactory.getInstance().createHtmlTextBalloonBuilder(string, com.intellij.openapi.ui.MessageType.INFO, null)
      popupBuilder.setHideOnKeyOutside(true).setHideOnAction(true).setHideOnClickOutside(true).setHideOnCloseClick(true).setHideOnLinkClick(true).setHideOnFrameResize(true)
      currentPopup = popupBuilder.createBalloon()
      currentPopup.addListener(new JBPopupListener {
        override def onClosed(lightweightWindowEvent: LightweightWindowEvent): Unit = {
          isPopupOpen = false
          predTime = curTime
        }

        override def beforeShown(lightweightWindowEvent: LightweightWindowEvent): Unit = {}
      })
      currentPopup.show(new RelativePoint(editor.getContentComponent, point), Balloon.Position.above)
    })
  }

  /**
    * Immediately requests the server for documentation at the current editor position
    *
    * @param editor The editor
    */
  def quickDoc(editor: Editor): Unit = {
    if (editor == this.editor) {
      val caretPos = editor.getCaretModel.getLogicalPosition
      val pointPos = editor.logicalPositionToXY(caretPos)
      val currentTime = System.nanoTime()
      ApplicationManager.getApplication.executeOnPooledThread(new Runnable {
        override def run(): Unit = requestAndShowDoc(currentTime, caretPos, pointPos)
      })
      predTime = currentTime
    } else {
      LOG.warn("Not same editor!")
    }
  }

  /**
    * Requests the Hover information
    *
    * @param editor The editor
    * @param offset The offset in the editor
    * @return The information
    */
  def requestDoc(editor: Editor, offset: Int): String = {
    if (editor == this.editor) {
      val serverPos = Utils.logicalToLSPPos(editor.offsetToLogicalPosition(offset))
      val request = requestManager.hover(new TextDocumentPositionParams(identifier, serverPos))
      if (request != null) {
        try {
          val response = request.get(HOVER_TIMEOUT, TimeUnit.MILLISECONDS)
          HoverHandler.getHoverString(response)
        } catch {
          case e: TimeoutException => LOG.warn(e)
            ""
        }
      } else {
        ""
      }
    } else {
      LOG.warn("Not same editor")
      ""
    }
  }

  /**
    * Handles the DocumentChanged events
    *
    * @param event The DocumentEvent
    */
  def documentChanged(event: DocumentEvent): Unit = {
    if (event.getDocument == editor.getDocument) {
      predTime = System.nanoTime() //So that there are no hover events while typing
      changesParams.getTextDocument.setVersion({
        version += 1
        version
      })
      syncKind match {
        case TextDocumentSyncKind.None =>
        case TextDocumentSyncKind.Incremental =>
          val changeEvent = changesParams.getContentChanges.get(0)
          val newText = event.getNewFragment
          val offset = event.getOffset
          val length = event.getNewLength
          val range = new Range(Utils.offsetToLSPPos(editor, offset), Utils.offsetToLSPPos(editor, offset + length))
          changeEvent.setRange(range)
          changeEvent.setRangeLength(length)
          changeEvent.setText(newText.toString)

        case TextDocumentSyncKind.Full =>
          changesParams.getContentChanges.get(0).setText(editor.getDocument.getText())
      }
      requestManager.didChange(changesParams)
    } else {
      LOG.error("Wrong document for the EditorEventManager")
    }
  }

  /**
    * Notifies the server that the corresponding document has been saved
    */
  def documentSaved(): Unit = {
    val params: DidSaveTextDocumentParams = new DidSaveTextDocumentParams(identifier, editor.getDocument.getText)
    requestManager.didSave(params)
  }

  /**
    * Notifies the server that the corresponding document has been closed
    */
  def documentClosed(): Unit = {
    if (isOpen) {
      requestManager.didClose(new DidCloseTextDocumentParams(identifier))
      isOpen = false
      editorToManager.remove(editor)
      uriToManager.remove(Utils.editorToURIString(editor))
    } else {
      LOG.warn("Editor " + editor + " was already closed")
    }
  }

  /**
    * Returns the completion suggestions given a position
    *
    * @param pos The LSP position
    * @return The suggestions
    */
  def completion(pos: Position): Iterable[_ <: LookupElement] = {
    val request = requestManager.completion(new TextDocumentPositionParams(identifier, pos))
    if (request != null) {
      try {
        val res = request.get(COMPLETION_TIMEOUT, TimeUnit.MILLISECONDS)
        import scala.collection.JavaConverters._
        val completion /*: CompletionList | List[CompletionItem] */ = if (res.isLeft) res.getLeft.asScala else res.getRight
        completion match {
          case c: CompletionList => c.getItems.asScala.map(item => LookupElementBuilder.create(item.getLabel).withPresentableText(item.getLabel).withAutoCompletionPolicy(AutoCompletionPolicy.SETTINGS_DEPENDENT))
          case l: List[CompletionItem@unchecked] => l.map(item => {
            LookupElementBuilder.create(item.getLabel).withPresentableText(item.getLabel).withAutoCompletionPolicy(AutoCompletionPolicy.SETTINGS_DEPENDENT)
          })

        }
      }
      catch {
        case e: TimeoutException =>
          LOG.warn(e)
          Iterable.empty
      }
    } else Iterable.empty
  }


  //TODO Manual
  /**
    * Indicates that the document will be saved
    */
  def willSave(): Unit = {
    requestManager.willSave(new WillSaveTextDocumentParams(identifier, TextDocumentSaveReason.Manual))
  }

  /**
    * Returns the references given the position of the word to search for
    *
    * @param pos A logical position
    * @return An array of PsiReference
    */
  def references(pos: LogicalPosition): Array[PsiReference] = {
    LOG.info("References")
    val lspPos = Utils.logicalToLSPPos(pos)
    val params = new ReferenceParams(new ReferenceContext(false))
    params.setPosition(lspPos)
    params.setTextDocument(identifier)
    val request = requestManager.references(params)
    if (request != null) {
      try {
        val res = request.get(REFERENCES_TIMEOUT, TimeUnit.MILLISECONDS)
        import scala.collection.JavaConverters._
        res.asScala.map(l => {
          val start = l.getRange.getStart
          val end = l.getRange.getEnd
          val logicalStart = Utils.LSPPosToOffset(editor, start)
          val logicalEnd = Utils.LSPPosToOffset(editor, end)
          val name = editor.getDocument.getText(new TextRange(logicalStart, logicalEnd))
          LSPPsiElement(name, editor.getProject, logicalStart, logicalEnd).getReference
        }).toArray
      } catch {
        case e: TimeoutException =>
          LOG.warn(e)
          Array()
      }
    } else Array.empty
  }

  /**
    * Applies the diagnostics to the document
    *
    * @param diagnostics The diagnostics to apply from the server
    */
  def diagnostics(diagnostics: Iterable[Diagnostic]): Unit = {
    ApplicationManager.getApplication.invokeLater(() => {
      diagnosticsHighlights.foreach(highlight => editor.getMarkupModel.removeHighlighter(highlight.rangeHighlighter))
      diagnosticsHighlights.clear()
    })
    for (diagnostic <- diagnostics) {
      val code = diagnostic.getCode
      val message = diagnostic.getMessage
      val source = diagnostic.getSource
      val range = diagnostic.getRange
      val severity = diagnostic.getSeverity
      val (start, end) = ApplicationManager.getApplication.runReadAction(new Computable[(Int, Int)] {
        override def compute(): (Int, Int) = (Utils.LSPPosToOffset(editor, range.getStart), Utils.LSPPosToOffset(editor, range.getEnd))
      })

      val markupModel = editor.getMarkupModel
      val colorScheme = editor.getColorsScheme

      val (effectType, effectColor, layer) = severity match {
        case null => null
        case DiagnosticSeverity.Error => (EffectType.WAVE_UNDERSCORE, Color.RED, HighlighterLayer.ERROR)
        case DiagnosticSeverity.Warning => (EffectType.WAVE_UNDERSCORE, Color.YELLOW, HighlighterLayer.WARNING)
        case DiagnosticSeverity.Information => (EffectType.WAVE_UNDERSCORE, Color.GRAY, HighlighterLayer.WARNING)
        case DiagnosticSeverity.Hint => (EffectType.BOLD_DOTTED_LINE, Color.GRAY, HighlighterLayer.WARNING)
      }
      ApplicationManager.getApplication.invokeLater(() => {
        diagnosticsHighlights
          .add(DiagnosticRangeHighlighter(markupModel.addRangeHighlighter(start, end, layer,
            new TextAttributes(colorScheme.getDefaultForeground, colorScheme.getDefaultBackground, effectColor, effectType, Font.PLAIN), HighlighterTargetArea.EXACT_RANGE),
            message, source, code))
      })
    }
  }

  /**
    * Rename a symbol in the document
    *
    * @param renameTo The new name
    */
  def rename(renameTo: String): Unit = {
    val servPos = Utils.logicalToLSPPos(editor.offsetToLogicalPosition(editor.getCaretModel.getCurrentCaret.getOffset))
    val params = new RenameParams(identifier, servPos, renameTo)
    val request = requestManager.rename(params)
    if (request != null) request.thenAccept(res => WorkspaceEditHandler.applyEdit(res))
  }

  /**
    * Reformat the whole document
    */
  def reformat(): Unit = {
    val params = new DocumentFormattingParams()
    params.setTextDocument(identifier)
    val options = new FormattingOptions()
    params.setOptions(options)
    val request = requestManager.formatting(params)
    if (request != null) request.thenAccept(formatting => applyEdit(edits = formatting.asScala))
  }

  /**
    * Applies the edits given a version
    *
    * @param version The version of the changes ; If it is lower than the current version, they are discarded
    * @param edits   The edits
    * @return True if the edits have been applied, false otherwise
    */
  def applyEdit(version: Int = Int.MaxValue, edits: Iterable[TextEdit]): Boolean = {
    if (version >= this.version) {
      edits.foreach(edit => {
        val text = edit.getNewText
        val range = edit.getRange
        ApplicationManager.getApplication.invokeLater(() => {
          val start = Utils.LSPPosToOffset(editor, range.getStart)
          val end = Utils.LSPPosToOffset(editor, range.getEnd)
          val document = editor.getDocument
          if (document.isWritable) {
            if (text == "") {
              val runnable = new Runnable {
                override def run(): Unit = ApplicationManager.getApplication.runWriteAction(new Runnable {
                  override def run(): Unit = {
                    document.deleteString(start, end)
                    FileDocumentManager.getInstance().saveDocument(document)
                  }
                })
              }
              CommandProcessor.getInstance().executeCommand(editor.getProject, runnable, "Delete string", "LSPPlugin", document)
            } else if (end - start <= 0) {
              val runnable = new Runnable {
                override def run(): Unit = ApplicationManager.getApplication.runWriteAction(new Runnable {
                  override def run(): Unit = {
                    document.insertString(start, text)
                    FileDocumentManager.getInstance().saveDocument(document)
                  }
                })
              }
              CommandProcessor.getInstance().executeCommand(editor.getProject, runnable, "Insert " + text, "LSPPlugin", document)

            } else {
              val runnable = new Runnable {
                override def run(): Unit = ApplicationManager.getApplication.runWriteAction(new Runnable {
                  override def run(): Unit = {
                    document.replaceString(start, end, text)
                    FileDocumentManager.getInstance().saveDocument(document)
                  }
                })
              }
              CommandProcessor.getInstance().executeCommand(editor.getProject, runnable, "Replace " + text, "LSPPlugin", document)
            }
          } else {
            LOG.warn("Document " + Utils.editorToURIString(editor) + " is read-only")
          }
        })
      })
      true
    } else {
      false
    }
  }

  /**
    * Reformat the text currently selected in the editor
    */
  def reformatSelection(): Unit = {
    val params = new DocumentRangeFormattingParams()
    params.setTextDocument(identifier)
    val selectionModel = editor.getSelectionModel
    val start = selectionModel.getSelectionStart
    val end = selectionModel.getSelectionEnd
    val startingPos = Utils.offsetToLSPPos(editor, start)
    val endPos = Utils.offsetToLSPPos(editor, end)
    params.setRange(new Range(startingPos, endPos))
    val options = new FormattingOptions()
    params.setOptions(options)
    val request = requestManager.rangeFormatting(params)
    if (request != null) request.thenAccept(formatting => applyEdit(edits = formatting.asScala))
  }

  /**
    * Calls completion or signatureHelp if the character typed was a trigger characte
    *
    * @param c The character just typed
    */
  def characterTyped(c: Char): Unit = {
    if (completionTriggers.contains(c.toString)) {
      //completion(Utils.offsetToLSPPos(editor,editor.getCaretModel.getCurrentCaret.getOffset))
    } else if (signatureTriggers.contains(c.toString)) {

    }
  }
}