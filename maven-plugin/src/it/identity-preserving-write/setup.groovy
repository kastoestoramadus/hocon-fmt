import java.nio.file.Files

def target = new File(basedir, 'src/main/resources/app.conf').toPath()
Files.createDirectories(target.parent)
Files.writeString(target, 'a   =   1\n')
if (target.fileSystem.supportedFileAttributeViews().contains('unix')) {
    Files.setAttribute(target, 'unix:mode', 02750)
    Files.createLink(new File(basedir, 'old-content').toPath(), target)
    Files.createSymbolicLink(target.resolveSibling('alias.conf'), target.fileName)
}

return true
