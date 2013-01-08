package demo.azavea

import javax.ws.rs.core._
import javax.ws.rs._

@Path("/hello")
class HelloResource {
  @GET
  def hello = 
    Response.ok("<h1>Hello World</h1>").`type`("text/html").build()
}
