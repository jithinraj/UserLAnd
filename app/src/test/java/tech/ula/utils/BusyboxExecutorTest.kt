package tech.ula.utils

import com.nhaarman.mockitokotlin2.whenever
import kotlinx.coroutines.runBlocking
import org.junit.Assert.* // ktlint-disable no-wildcard-imports
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnitRunner
import java.io.File
import java.nio.file.Files

@RunWith(MockitoJUnitRunner::class)
class BusyboxExecutorTest {

    @get:Rule val tempFolder = TemporaryFolder()

    lateinit var mockFilesDir: File

    lateinit var mockExternalStorage: File

    lateinit var mockFilesystemDir: File

    @Mock lateinit var mockDefaultPreferences: DefaultPreferences

    @Mock lateinit var mockBusyboxWrapper: BusyboxWrapper

    private val testFilesystemDirName = "filesystem"
    private val testProotDebugLevel = "9"
    private val prootDebugFileName = "PRoot_Debug_Log"

    private val outputCollection = mutableListOf<String>()
    private val testListener: (String) -> Unit = { outputCollection.add(it) }

    private lateinit var busyboxExecutor: BusyboxExecutor

    // Normally, the addition of busybox allows us to send the 'actual' command as a complete
    // string. Testing (and therefore just using the JVM runtime as the command executor) requires
    // the commands to be complete space-delimited.
    private fun String.toExecutableList(): List<String> {
        return this.split(" ").toList()
    }

    @Before
    fun setup() {
        outputCollection.clear()

        mockFilesDir = tempFolder.newFolder("files")
        mockExternalStorage = tempFolder.newFolder("external")

        mockFilesystemDir = File("${mockFilesDir.absolutePath}/$testFilesystemDirName")
        mockFilesDir.mkdirs()

        busyboxExecutor = BusyboxExecutor(mockFilesDir, mockExternalStorage, mockDefaultPreferences, mockBusyboxWrapper)
    }

    private fun stubBusyboxIsPresent(present: Boolean) {
        whenever(mockBusyboxWrapper.busyboxIsPresent(mockFilesDir)).thenReturn(present)
    }

    private fun stubBusyboxCommand(command: String) {
        whenever(mockBusyboxWrapper.addBusybox(command)).thenReturn(command.toExecutableList())
    }

    private fun stubBusyboxEnv() {
        whenever(mockBusyboxWrapper.getBusyboxEnv(mockFilesDir)).thenReturn(hashMapOf())
    }

    private fun stubProotIsPresent(present: Boolean) {
        whenever(mockBusyboxWrapper.prootIsPresent(mockFilesDir)).thenReturn(present)
    }

    private fun stubExecutionScriptIsPresent(present: Boolean) {
        whenever(mockBusyboxWrapper.executionScriptIsPresent(mockFilesDir)).thenReturn(present)
    }

    private fun stubProotDebuggingEnabled(enabled: Boolean) {
        if (enabled) {
            whenever(mockDefaultPreferences.getProotDebuggingEnabled()).thenReturn(true)
        } else {
            whenever(mockDefaultPreferences.getProotDebuggingEnabled()).thenReturn(false)
        }
        // Stub these regardless to ensure unwanted writes don't occur
        whenever(mockDefaultPreferences.getProotDebuggingLevel()).thenReturn(testProotDebugLevel)
        whenever(mockDefaultPreferences.getProotDebugLogLocation()).thenReturn("${mockExternalStorage.absolutePath}/$prootDebugFileName")
    }

    private fun stubProotCommand(command: String) {
        whenever(mockBusyboxWrapper.addBusyboxAndProot(command)).thenReturn(command.toExecutableList())
    }

    private fun stubProotEnv() {
        whenever(mockBusyboxWrapper.getProotEnv(mockFilesDir, mockFilesystemDir, testProotDebugLevel, mockExternalStorage))
                .thenReturn(hashMapOf())
    }

    @Test
    fun `Successfully executes legal commands, 'adding' busybox`() {
        val testOutput = "hello"
        val testCommand = "echo $testOutput"
        stubBusyboxIsPresent(true)
        stubBusyboxCommand(testCommand)
        stubBusyboxEnv()

        val result = busyboxExecutor.executeCommand(testCommand, testListener)

        assertEquals(1, outputCollection.size)
        assertEquals(testOutput, outputCollection[0])
        assertTrue(result is SuccessfulExecution)
    }

    @Test
    fun `Returns MissingExecutionAsset if busybox is not present`() {
        val testOutput = "hello"
        val testCommand = "echo $testOutput"
        stubBusyboxIsPresent(false)

        val result = busyboxExecutor.executeCommand(testCommand, testListener)
        assertTrue(result is MissingExecutionAsset)
        result as MissingExecutionAsset
        assertEquals("busybox", result.asset)
    }

    @Test()
    fun `Fails to execute illegal commands, 'adding' busybox`() {
        val testCommand = "badCommand"
        stubBusyboxIsPresent(true)
        stubBusyboxCommand(testCommand)
        stubBusyboxEnv()

        val result = busyboxExecutor.executeCommand(testCommand, testListener)
        assertTrue(result is FailedExecution)
    }

    @Test
    fun `Successfully executes legal commands, 'adding' proot and busybox`() {
        val testOutput = "hello"
        val testCommand = "echo $testOutput"

        stubBusyboxIsPresent(true)
        stubProotIsPresent(true)
        stubExecutionScriptIsPresent(true)
        stubProotDebuggingEnabled(false)
        stubProotCommand(testCommand)
        stubProotEnv()

        val result = busyboxExecutor.executeProotCommand(testCommand, testFilesystemDirName, true, hashMapOf(), testListener)

        assertTrue(result is SuccessfulExecution)
        assertEquals(1, outputCollection.size)
        assertEquals(testOutput, outputCollection[0])
    }

    @Test
    fun `Returns an OngoingExecution result with process if command should not terminate`() {
        val testOutput = "hello"
        val testCommand = "echo $testOutput"

        stubBusyboxIsPresent(true)
        stubProotIsPresent(true)
        stubExecutionScriptIsPresent(true)
        stubProotDebuggingEnabled(false)
        stubProotCommand(testCommand)
        stubProotEnv()

        val result = busyboxExecutor.executeProotCommand(testCommand, testFilesystemDirName, false, hashMapOf(), testListener)

        assertTrue(result is OngoingExecution)
        result as OngoingExecution
        val resultProcess = result.process
        assertEquals(0, resultProcess.waitFor())
    }

    @Test
    fun `Overwrites PRoot debug logs with redirected output if logging is enabled`() {
        val testOutput = "hello"
        val testCommand = "echo $testOutput"
        val debugFile = File("${mockExternalStorage.absolutePath}/$prootDebugFileName")
        debugFile.createNewFile()
        debugFile.writeText("original text")

        stubBusyboxIsPresent(true)
        stubProotIsPresent(true)
        stubExecutionScriptIsPresent(true)
        stubProotDebuggingEnabled(true)
        stubProotCommand(testCommand)
        stubProotEnv()

        val result = runBlocking {
            busyboxExecutor.executeProotCommand(
                    testCommand,
                    testFilesystemDirName,
                    true,
                    hashMapOf(),
                    testListener,
                    this)
        }

        assertTrue(result is SuccessfulExecution)
        assertEquals(0, outputCollection.size)

        val debugText = debugFile.readText()
        assertEquals(testOutput, debugText.trim())
    }

    @Test
    fun `executeProotCommand returns MissingExecutionAsset with busybox if busybox is missing`() {
        val testOutput = "hello"
        val testCommand = "echo $testOutput"

        stubBusyboxIsPresent(false)

        val result = busyboxExecutor.executeProotCommand(testCommand, testFilesystemDirName, true, hashMapOf(), testListener)

        assertTrue(result is MissingExecutionAsset)
        result as MissingExecutionAsset
        assertEquals("busybox", result.asset)
    }

    @Test
    fun `executeProotCommand returns MissingExecutionAsset with proot if proot is missing`() {
        val testOutput = "hello"
        val testCommand = "echo $testOutput"

        stubBusyboxIsPresent(true)
        stubProotIsPresent(false)

        val result = busyboxExecutor.executeProotCommand(testCommand, testFilesystemDirName, true, hashMapOf(), testListener)

        assertTrue(result is MissingExecutionAsset)
        result as MissingExecutionAsset
        assertEquals("proot", result.asset)
    }

    @Test
    fun `executeProotCommand returns MissingExecutionAsset with the exec script if the exec script is missing`() {
        val testOutput = "hello"
        val testCommand = "echo $testOutput"

        stubBusyboxIsPresent(true)
        stubProotIsPresent(true)
        stubExecutionScriptIsPresent(false)

        val result = busyboxExecutor.executeProotCommand(testCommand, testFilesystemDirName, true, hashMapOf(), testListener)

        assertTrue(result is MissingExecutionAsset)
        result as MissingExecutionAsset
        assertEquals("execution script", result.asset)
    }

    @Test()
    fun `Fails to execute illegal commands, 'adding' proot and busybox`() {
        val testCommand = "badCommand"

        stubBusyboxIsPresent(true)
        stubProotIsPresent(true)
        stubExecutionScriptIsPresent(true)
        stubProotDebuggingEnabled(false)
        stubProotCommand(testCommand)
        stubProotEnv()

        val result = busyboxExecutor.executeProotCommand(testCommand, testFilesystemDirName, true)

        assertTrue(result is FailedExecution)
    }

    @Test
    fun `Recursively deletes files`() = runBlocking {
        val testDirName = "testDir"
        val testDir = File("${mockFilesDir.absolutePath}/$testDirName")
        val testFileName = "testFile"
        val testFile = File("${testDir.absolutePath}/$testFileName")

        testDir.mkdirs()
        assertTrue(testDir.exists() && testDir.isDirectory)
        testFile.createNewFile()
        assertTrue(testFile.exists())

        val command = "rm -rf ${testDir.absolutePath}"
        stubBusyboxIsPresent(true)
        stubBusyboxCommand(command)
        stubBusyboxEnv()

        val result = busyboxExecutor.recursivelyDelete(testDir.absolutePath)

        assertTrue(result is SuccessfulExecution)
        assertTrue(mockFilesDir.exists())
        assertFalse(testDir.exists())
        assertFalse(testFile.exists())
    }

    @Test
    fun `Calling recursivelyDelete on a single file also works`() = runBlocking {
        val testFileName = "testFile"
        val testFile = File("${mockFilesDir.absolutePath}/$testFileName")

        testFile.createNewFile()
        assertTrue(testFile.exists())

        val command = "rm -rf ${testFile.absolutePath}"
        stubBusyboxIsPresent(true)
        stubBusyboxCommand(command)
        stubBusyboxEnv()

        val result = busyboxExecutor.recursivelyDelete(testFile.absolutePath)

        assertTrue(result is SuccessfulExecution)
        assertTrue(mockFilesDir.exists())
        assertFalse(testFile.exists())
    }

    @Test
    fun `recursivelyDelete does not follow symbolic links`() = runBlocking {
        val originalTestDirName = "testDir"
        val originalTestDir = File("${mockExternalStorage.absolutePath}/$originalTestDirName")
        val originalTestFileName = "testFile"
        val originalTestFile = File("${originalTestDir.absolutePath}/$originalTestFileName")

        originalTestDir.mkdirs()
        assertTrue(originalTestDir.exists() && originalTestDir.isDirectory)
        originalTestFile.createNewFile()
        assertTrue(originalTestFile.exists())

        val symbolicDirLinkFile = File("$mockFilesDir/testSymDir")
        val symbolicDirLinkPath = symbolicDirLinkFile.toPath()
        assertFalse(symbolicDirLinkFile.exists())

        Files.createSymbolicLink(symbolicDirLinkPath, originalTestDir.toPath())
        assertTrue(symbolicDirLinkFile.exists())
        assertTrue(Files.isSymbolicLink(symbolicDirLinkPath))

        val linkedFile = File("${symbolicDirLinkFile.absolutePath}/$originalTestFileName")
        assertTrue(linkedFile.exists())

        val command = "rm -rf ${symbolicDirLinkFile.absolutePath}"
        stubBusyboxIsPresent(true)
        stubBusyboxCommand(command)
        stubBusyboxEnv()

        val result = busyboxExecutor.recursivelyDelete(symbolicDirLinkFile.absolutePath)

        assertTrue(result is SuccessfulExecution)
        assertFalse(symbolicDirLinkFile.exists())
        assertFalse(linkedFile.exists())
        assertTrue(originalTestDir.exists())
        assertTrue(originalTestFile.exists())
    }
}