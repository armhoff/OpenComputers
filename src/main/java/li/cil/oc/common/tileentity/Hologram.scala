package li.cil.oc.common.tileentity

import cpw.mods.fml.relauncher.{Side, SideOnly}
import li.cil.oc.api.network._
import li.cil.oc.server.{PacketSender => ServerPacketSender}
import li.cil.oc.{Settings, api}
import net.minecraft.nbt.NBTTagCompound
import net.minecraft.util.AxisAlignedBB
import net.minecraftforge.common.ForgeDirection

class Hologram extends Environment with SidedEnvironment {
  val node = api.Network.newNode(this, Visibility.Network).
    withComponent("hologram").
    withConnector().
    create()

  val width = 3 * 16

  val height = 2 * 16 // 32 bit in an int

  val volume = new Array[Int](width * width)

  // Render scale.
  var scale = 1.0

  // Relative number of lit columns (for energy cost).
  var litRatio = -1.0

  // Whether we need to send an update packet/recompile our display list.
  var dirty = false

  // Time to wait before sending another update packet.
  var cooldown = 0

  var hasPower = true

  // ----------------------------------------------------------------------- //

  override def canConnect(side: ForgeDirection) = side != ForgeDirection.UP

  override def sidedNode(side: ForgeDirection) = node

  // ----------------------------------------------------------------------- //

  @Callback(doc = """function() -- Clears the hologram.""")
  def clear(computer: Context, args: Arguments): Array[AnyRef] = this.synchronized {
    for (i <- 0 until volume.length) volume(i) = 0
    dirty = true
    litRatio = 0
    null
  }

  @Callback(direct = true, doc = """function(x:number, z:number):number -- Returns the bit mask representing the specified column.""")
  def get(computer: Context, args: Arguments): Array[AnyRef] = this.synchronized {
    val x = args.checkInteger(0) - 1
    val z = args.checkInteger(1) - 1
    result(volume(x + z * width))
  }

  @Callback(direct = true, limit = 256, doc = """function(x:number, z:number, value:number) -- Set the bit mask for the specified column.""")
  def set(computer: Context, args: Arguments): Array[AnyRef] = this.synchronized {
    val x = args.checkInteger(0) - 1
    val z = args.checkInteger(1) - 1
    val value = args.checkInteger(2)
    volume(x + z * width) = value
    dirty = true
    litRatio = -1
    null
  }

  @Callback(direct = true, limit = 128, doc = """function(x:number, z:number, height:number) -- Fills a column to the specified height.""")
  def fill(computer: Context, args: Arguments): Array[AnyRef] = this.synchronized {
    val x = args.checkInteger(0) - 1
    val z = args.checkInteger(1) - 1
    val height = math.min(32, math.max(0, args.checkInteger(2)))
    // Bit shifts in the JVM only use the lowest five bits... so we have to
    // manually check the height, to avoid the shift being a no-op.
    volume(x + z * width) = if (height > 0) 0xFFFFFFFF >>> (32 - height) else 0
    dirty = true
    litRatio = -1
    null
  }

  @Callback(doc = """function():number -- Returns the render scale of the hologram.""")
  def getScale(computer: Context, args: Arguments): Array[AnyRef] = {
    result(scale)
  }

  @Callback(doc = """function(value:number) -- Set the render scale. A larger scale consumes more energy.""")
  def setScale(computer: Context, args: Arguments): Array[AnyRef] = {
    scale = math.max(0.333333, math.min(3, args.checkDouble(0)))
    ServerPacketSender.sendHologramScale(this)
    null
  }

  // ----------------------------------------------------------------------- //

  override def updateEntity() {
    super.updateEntity()
    if (isServer) {
      if (dirty) {
        cooldown -= 1
        if (cooldown <= 0) {
          dirty = false
          cooldown = 10
          ServerPacketSender.sendHologramSet(this)
        }
      }
      if (litRatio < 0) {
        litRatio = 0
        for (i <- 0 until volume.length) {
          if (volume(i) != 0) litRatio += 1
        }
        litRatio /= volume.length
      }

      hasPower = node.changeBuffer(-Settings.get.hologramCost * litRatio * scale) == 0
    }
  }

  // ----------------------------------------------------------------------- //

  override def shouldRenderInPass(pass: Int) = pass == 1

  override def getRenderBoundingBox = AxisAlignedBB.getAABBPool.getAABB(xCoord + 0.5 - 1.5 * scale, yCoord, zCoord - scale, xCoord + 0.5 + 1.5 * scale, yCoord + 0.25 + 2 * scale, zCoord + 0.5 + 1.5 * scale)

  // ----------------------------------------------------------------------- //

  override def readFromNBT(nbt: NBTTagCompound) {
    super.readFromNBT(nbt)
    nbt.getIntArray(Settings.namespace + "volume").copyToArray(volume)
    scale = nbt.getDouble(Settings.namespace + "scale")
  }

  override def writeToNBT(nbt: NBTTagCompound) = this.synchronized {
    super.writeToNBT(nbt)
    nbt.setIntArray(Settings.namespace + "volume", volume)
    nbt.setDouble(Settings.namespace + "scale", scale)
  }

  @SideOnly(Side.CLIENT)
  override def readFromNBTForClient(nbt: NBTTagCompound) {
    super.readFromNBTForClient(nbt)
    nbt.getIntArray("volume").copyToArray(volume)
    scale = nbt.getDouble("scale")
    dirty = true
  }

  override def writeToNBTForClient(nbt: NBTTagCompound) {
    super.writeToNBTForClient(nbt)
    nbt.setIntArray("volume", volume)
    nbt.setDouble("scale", scale)
  }
}
