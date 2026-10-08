import java.nio.file.Files

def target = new File(basedir, 'src/main/resources/app.conf').toPath()
assert Files.readString(target) == 'a = 1\n'
if (target.fileSystem.supportedFileAttributeViews().contains('unix')) {
    assert Files.readString(new File(basedir, 'old-content').toPath()) == 'a   =   1\n'
    assert Files.isSymbolicLink(target.resolveSibling('alias.conf'))
    assert (Files.getAttribute(target, 'unix:mode') & 07777) == 02750
}
