package opengpu.graphics

/** Shared test fixture without widening the graphics texture test ABI. */
private[opengpu] object ComputeTextureCoexistenceFixture {
  private val native=NativeTextureFetchTestData
  val root: BigInt=native.root
  val packet: BigInt=native.packet
  def roots(put:(BigInt,BigInt)=>Unit,offset:BigInt):Unit=native.roots(put,stage=2,offset=offset)
  def coordinates(put:(BigInt,BigInt)=>Unit,offset:BigInt):Unit=native.coordinates(put,offset=offset)
  def value(put:(BigInt,BigInt)=>Unit,byte:(BigInt,Int)=>Unit,unit:Int,offset:BigInt,raw:BigInt):BigInt =
    native.install(put,byte,unit,3,stage=2,offset=offset,raw=raw).expected(0)
}
