import numerato._

@enum(debug = false) class Status {
  val Enabled, Disabled, Deferred = Value
  val Unknown, Pending, Challenged = Value
}

@enum(debug = false) class Neighborhood(zip: Int, elevation: Double) {
  val UWS = Value(10024, 40)
  val Chelsea = Value(10011, 19)
}
