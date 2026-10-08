package ww86.hocon_fmt.interop.cats

/** Everything a replacement must carry over to be the same file: owner, group and every mode bit,
  * setuid, setgid and sticky included. A staged copy that cannot be given all three is not proof
  * that the file survives formatting unchanged.
  */
final private[interop] case class FileIdentity(owner: Int, group: Int, mode: Int) derives CanEqual

private[interop] object FileIdentity {

  /** The twelve mode bits a file carries; `stat` also reports its type in the same field. */
  val modeBits = 0xfff
}
