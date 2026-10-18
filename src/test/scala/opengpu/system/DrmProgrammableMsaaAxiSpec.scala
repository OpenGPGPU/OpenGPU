package opengpu.system

/** Guest vertex/fragment instructions, UV gradients and packed4/2/1 mip chain.
  * General CUs use the Linux16lane/8warp profile; private graphics stays4/2.
  */
class DrmProgrammableGuestImageAxiSpec extends ProgrammableTextureAxiChecks(
  None, drmImageSide = 64, drmGuestTexture = true)

class DrmProgrammable2xAxiSpec extends ProgrammableTextureAxiChecks(
  None, drmImageSide = 64, drmSampleMode = 1, drmGuestTexture = true)

class DrmProgrammable4xAxiSpec extends ProgrammableTextureAxiChecks(
  None, drmImageSide = 64, drmSampleMode = 2, drmGuestTexture = true)
