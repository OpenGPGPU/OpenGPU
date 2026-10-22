package opengpu.graphics

/** GUC1 describes the native checked uniform-word ISA and resource limits.
  * This is not an API, compiler-lowering, throughput or enclosing-clock claim.
  * Hosts expose it only when both native stages have the uniform provider.
  */
object NativeUniformCapabilities {
  val base=0x3a0;val wordCount=16;val end=base+4*wordCount
  val magic=BigInt("47554301",16);val version=1
  val features=BigInt(0x7f)
  val fields=Seq("MAGIC","WORDS","VERSION","FEATURES","STAGES","BLOCKS_PER_STAGE",
    "BINDINGS","BLOCK_BYTES","DEFAULT_VERTEX_COMPONENTS","DEFAULT_FRAGMENT_COMPONENTS",
    "BLOCK_ALIGNMENT","ROOT_BYTES","SCALAR_LOAD_BASE","VECTOR_LOAD_BASE","VECTOR_LOAD_UNMASKED_BASE","DEFAULT_BLOCK")
  val scalarLoadBase=BigInt("0e00000b",16)
  val vectorLoadBase=BigInt("1c00002b",16)
  val vectorLoadUnmaskedBase=BigInt("1e00002b",16)
  val words: Seq[BigInt]=Seq(magic,BigInt(wordCount),BigInt(version),features,BigInt(3),
    BigInt(ShaderUniformLimits.blocksPerStage),BigInt(ShaderUniformLimits.bindings),BigInt(ShaderUniformLimits.blockBytes),
    BigInt(ShaderUniformLimits.defaultComponents(0)),BigInt(ShaderUniformLimits.defaultComponents(1)),
    BigInt(ShaderUniformLimits.offsetAlignment),BigInt(64),scalarLoadBase,vectorLoadBase,vectorLoadUnmaskedBase,
    BigInt(ShaderUniformLimits.defaultBlock))
  require(base>=opengpu.system.ComputeResourceCapabilities.end && (base&31)==0 && end<=0x400)
  require(words.size==wordCount && fields.size==wordCount && words.forall(v=>v>=0 && v<(BigInt(1)<<32)))
  def header(prefix: String): String={
    val constants=Seq("CAPABILITIES"->s"0x${base.toHexString}u","CAPABILITY_MAGIC"->s"0x${magic.toString(16)}u",
      "CAPABILITY_WORDS"->s"${wordCount}u","CAPABILITY_VERSION"->s"${version}u",
      "CAP_SCALAR"->"1u","CAP_VECTOR"->"2u","CAP_ACTIVE_LANE_COALESCING"->"4u",
      "CAP_CHECKED_PRIVATE_PREFIX"->"8u","CAP_READ_ONLY_VIEWS"->"16u","CAP_CANCELLATION_DRAIN"->"32u",
      "CAP_NATIVE_DRAW_ROOTS"->"64u","CAP_STAGE_VERTEX"->"1u","CAP_STAGE_FRAGMENT"->"2u")++
      fields.zipWithIndex.map{case(name,word)=>("CAPABILITY_"+name+"_WORD",s"${word}u")}
    constants.map{case(name,value)=>s"#define $prefix$name $value\n"}.mkString
  }
}
