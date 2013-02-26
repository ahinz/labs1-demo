package demo.azavea

import javax.ws.rs.core._
import javax.ws.rs._

import org.geotools.data.simple._
import org.opengis.feature.simple._
import org.geotools.data.shapefile._

import geotrellis.feature.Point
import geotrellis._
import geotrellis.raster.op._
import geotrellis.rest.op._

import com.vividsolutions.jts.geom.{Point => JtsPoint}

object Context {
  def loadShapefile(f: String) = {
    // Get a reference to the shapefile
    val cl = getClass().getClassLoader()
    val shapefileLoc = cl.getResource(f)

    // Extract the features as GeoTools 'SimpleFeatures'
    val ftItr:SimpleFeatureIterator = new ShapefileDataStore(shapefileLoc).
      getFeatureSource().
      getFeatures().
      features()

    var gtFeatures:Seq[SimpleFeature] = Seq.empty
    while(ftItr.hasNext()) gtFeatures = gtFeatures :+ ftItr.next()

    // Convert to GeoTrellis features
    gtFeatures map { ft =>
      val geom = ft.getAttribute(0).asInstanceOf[JtsPoint]
      val nSamples = ft.getAttribute(1).asInstanceOf[Integer].toInt
      Point(geom,nSamples)
    }
  }

  // Load sightings from the shapefile
  val features:Seq[Point[Int]] = loadShapefile("sightings.shp")

  // Create a server to run our ops
  val catalog = process.Catalog("", Map.empty, "", "")
  val server = process.Server("demo", catalog)

  val baseRamp = geotrellis.data.ColorRamps.RedToAmberToGreen.colors
  val ramp = new geotrellis.data.ColorRamp {
    val colors = 0x00000000 +: baseRamp.map { c => (c & 0xFFFFFF00) | (0xFF * 50/100) }
  }
}

@Path("/density")
class Resource {
  @GET
  def density(
    @QueryParam("bbox")
    bbox: String,

    @QueryParam("width")
    cols: String,

    @QueryParam("height")
    rows: String,

    @QueryParam("kSize")
    @DefaultValue("301")
    kSize: String,

    @QueryParam("style")
    @DefaultValue("50")
    spread: String
  ) = {
    val features = Context.features

    val rasterExtentOp:Op[RasterExtent] =
      string.ParseRasterExtent(bbox, cols, rows)

    val sizeInKmOp:Op[Int] = string.ParseInt(kSize)

    val spreadInKmOp:Op[Double] = string.ParseDouble(spread)

    // spread and size are defined in real units (km) and
    // need to be converted to cell sizes
    val cellSize:Op[Double] = logic.Do(rasterExtentOp)(_.cellwidth)
    val spreadOp:Op[Double] =
      logic.Do(cellSize, spreadInKmOp) { (cellSize, spreadKm) =>
        spreadKm * 1000.0 / cellSize
      }

    val sizeOp:Op[Int] =
      logic.Do(cellSize, sizeInKmOp) { (cellSize, sizeKm) =>
        val size = (sizeKm * 1000.0 / cellSize).toInt
        size - (size % 2) + 1 // Size must be odd
      }

    val kernelOp = focal.CreateGaussianRaster(sizeOp, cellSize, spreadOp, 100.0)
    val kernelDensityOp = focal.KernelDensity(features, (x:Int) => x,
                                              kernelOp, rasterExtentOp)

    //val pngOp = io.SimpleRenderPng(kernelDensityOp, Context.ramp)
    val kd = Context.server.run(kernelDensityOp)
    val hist = statistics.op.stat.GetHistogram(kd)
    val colors = Context.ramp.colors
    val nColors = colors.length
    val cb = geotrellis.data.ColorBreaks((1 to nColors).map { c => c * 300 / nColors }.toArray, colors)
    val pngOp = io.RenderPng(kd, cb, hist, 0)
    val png = Context.server.run(pngOp)

    Response.ok(png).`type`("image/png").build()
  }
}
