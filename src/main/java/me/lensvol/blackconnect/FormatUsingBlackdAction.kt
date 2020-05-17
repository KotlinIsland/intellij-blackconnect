package me.lensvol.blackconnect

import com.intellij.notification.NotificationDisplayType
import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.UndoConfirmationPolicy
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.annotations.NotNull
import org.jetbrains.annotations.Nullable
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.URL


class FormatUsingBlackdAction : AnAction() {
    private fun callBlackd(
            path: String,
            sourceCode: String,
            pyi: Boolean = false,
            lineLength: Int = 88,
            fastMode: Boolean = false,
            skipStringNormalization: Boolean = false
    ): Pair<Int, String> {
        val url = URL(path)

        try {
            with(url.openConnection() as HttpURLConnection) {
                requestMethod = "POST"
                doOutput = true

                setRequestProperty("X-Protocol-Version", "1")
                setRequestProperty("X-Fast-Or-Safe", if (fastMode) "fast" else "safe")
                setRequestProperty("X-Line-Length", lineLength.toString())

                if (pyi) {
                    setRequestProperty("X-Python-Variant", "pyi")
                }

                if (skipStringNormalization) {
                    setRequestProperty("X-Skip-String-Normalization", "yes")
                }

                try {
                    outputStream.use { os ->
                        val input: ByteArray = sourceCode.toByteArray()
                        os.write(input, 0, input.size)
                    }

                    inputStream.bufferedReader().use { return Pair(responseCode, it.readText()) }
                } catch (e: IOException) {
                    return Pair(responseCode, errorStream.readBytes().toString())
                }
            }
        } catch (e: ConnectException) {
            return Pair(-1, e.message ?: "Connection failed.")
        }
    }

    private fun showError(project: Project, text: String) {
        NotificationGroup("BlackConnect", NotificationDisplayType.BALLOON, true)
                .createNotification(text, NotificationType.ERROR)
                .notify(project)
    }

    override fun actionPerformed(event: AnActionEvent) {
        event.project?.let { project ->
            FileEditorManagerEx.getInstance(project).selectedTextEditor?.let { editor ->
                val configuration = BlackConnectSettingsConfiguration.getInstance(project)

                val vFile: VirtualFile? = event.getData(PlatformDataKeys.VIRTUAL_FILE)
                val fileName = vFile?.name ?: "unknown"

                val progressIndicator = EmptyProgressIndicator()
                progressIndicator.isIndeterminate = true

                val projectService = project.service<BlackConnectProgressTracker>()
                projectService.registerOperationOnPath(vFile!!.path, progressIndicator)

                ProgressManager.getInstance().runProcessWithProgressAsynchronously(
                        object : Task.Backgroundable(project, "Calling blackd") {
                            override fun run(indicator: ProgressIndicator) {
                                reformatCodeInCurrentTab(configuration, editor, fileName, project)
                            }
                        },
                        progressIndicator
                )
            }
        }
    }

    private fun reformatCodeInCurrentTab(configuration: BlackConnectSettingsConfiguration, editor: @Nullable Editor, fileName: @NotNull String, project: @Nullable Project) {
        val progressIndicator = ProgressManager.getGlobalProgressIndicator()
        if(progressIndicator?.isCanceled == true)
            return

        val (responseCode, responseText) = callBlackd(
                "http://" + configuration.hostname + ":" + configuration.port + "/",
                editor.document.text,
                pyi = fileName.endsWith(".pyi"),
                lineLength = configuration.lineLength,
                fastMode = configuration.fastMode,
                skipStringNormalization = configuration.skipStringNormalization
        )

        if(progressIndicator?.isCanceled == true)
            return

        when (responseCode) {
            200 -> {
                ApplicationManager.getApplication().invokeLater {
                    ApplicationManager.getApplication().runWriteAction(Computable {
                        if(progressIndicator?.isCanceled == false) {
                            CommandProcessor.getInstance().executeCommand(
                                    project,
                                    {
                                        editor.document.setText(responseText)
                                    },
                                    "Reformat code using blackd",
                                    null,
                                    UndoConfirmationPolicy.DEFAULT,
                                    editor.document
                            )
                        }
                    })
                }
            }
            204 -> {
                // Nothing was modified, nothing to do here, move along.
            }
            400 -> {
                showError(project, "Source code contained syntax errors.")
            }
            500 -> {
                showError(project, "Internal error, please see blackd output.")
            }
            else -> {
                showError(project, "Something unexpected happened:\n$responseText")
            }
        }
    }

    override fun update(event: AnActionEvent) {
        val project: Project? = event.project
        if (project == null) {
            event.presentation.isEnabled = false
        } else {
            val vFile: VirtualFile? = event.getData(PlatformDataKeys.VIRTUAL_FILE)
            vFile?.name?.let { filename ->
                event.presentation.isEnabled = filename.endsWith(".py") || filename.endsWith(".pyi")
            }
        }
    }
}