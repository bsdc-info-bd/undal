package com.undal.syl.app.core.model

import com.google.firebase.Timestamp
import com.undal.syl.app.core.util.Money
import com.undal.syl.app.core.util.OrderStateMachine
import com.undal.syl.app.core.util.OrderStatus
import com.undal.syl.app.core.util.TimeDhaka

/**
 * The kitchen's view of an order (PART G · V-025 … V-048).
 *
 * The customer app and the kitchen app read the same `orders/{orderId}`
 * document. The customer cares about what happens next; the kitchen cares about
 * what to cook, where to send it, how long it has been sitting and how much cash
 * to collect — so the same document is parsed into this richer shape.
 */
data class VendorOrderLine(
    val name: String,
    val qty: Int,
    val unitAmountPaisa: Long,
    val note: String = ""
) {
    val lineTotalPaisa: Long get() = unitAmountPaisa * qty
}

/** Who moved the order. Used by the timeline (V-038). */
enum class OrderActor { CUSTOMER, KITCHEN, SYSTEM }

data class VendorTimelineStep(
    val status: OrderStatus,
    val label: String,
    val atMillis: Long?,
    val actor: OrderActor
) {
    val done: Boolean get() = atMillis != null
}

/** Kitchen queue tabs (V-041). */
enum class VendorQueueFilter(val label: String) {
    NEW("New"),
    PREPARING("Preparing"),
    READY("Ready"),
    OUT("Out"),
    DONE("Done"),
    CANCELLED("Cancelled");

    fun matches(status: OrderStatus): Boolean = when (this) {
        NEW -> status == OrderStatus.PLACED
        PREPARING -> status == OrderStatus.ACCEPTED
        READY -> status == OrderStatus.READY
        OUT -> status == OrderStatus.IN_DELIVERY
        DONE -> status == OrderStatus.DELIVERED
        CANCELLED -> status == OrderStatus.CANCELLED
    }
}

/** One bar in the week chart, built from real orders (V-014). */
data class VendorDayPoint(
    val dayKey: String,
    val label: String,
    val orders: Int,
    val revenuePaisa: Long
)

/** Today at a glance (V-011 … V-013, V-048). */
data class VendorDayStats(
    val orders: Int = 0,
    val revenuePaisa: Long = 0L,
    val pending: Int = 0,
    val active: Int = 0,
    val delivered: Int = 0,
    val cancelled: Int = 0,
    val averageTicketPaisa: Long = 0L,
    val topDish: String = ""
) {
    val pendingAndActive: Int get() = pending + active

    companion object {
        fun of(orders: List<VendorOrder>): VendorDayStats {
            if (orders.isEmpty()) return VendorDayStats()
            val delivered = orders.filter { it.status == OrderStatus.DELIVERED }
            val revenue = delivered.sumOf { it.collectedAmountPaisa ?: it.totalAmountPaisa }
            val topDish = orders
                .flatMap { order -> order.lines }
                .groupBy { it.name }
                .mapValues { entry -> entry.value.sumOf { it.qty } }
                .maxByOrNull { entry -> entry.value }
                ?.key
            return VendorDayStats(
                orders = orders.size,
                revenuePaisa = revenue,
                pending = orders.count { it.status == OrderStatus.PLACED },
                active = orders.count { OrderStateMachine.isActive(it.status) && it.status != OrderStatus.PLACED },
                delivered = delivered.size,
                cancelled = orders.count { it.status == OrderStatus.CANCELLED },
                averageTicketPaisa = if (delivered.isEmpty()) 0L else revenue / delivered.size,
                topDish = topDish.orEmpty()
            )
        }
    }
}

/** One line of the kitchen event stream (V-021). */
data class VendorStatusEvent(
    val orderId: String,
    val shortId: String,
    val atMillis: Long,
    val text: String
)

/**
 * One order, everything the kitchen needs and nothing else.
 */
data class VendorOrder(
    val id: String,
    val shortId: String,
    val status: OrderStatus,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val prepMinutes: Int,
    val prepEndsAtMillis: Long?,
    val acceptedAtMillis: Long?,
    val readyAtMillis: Long?,
    val inDeliveryAtMillis: Long?,
    val deliveredAtMillis: Long?,
    val collectedAmountPaisa: Long?,
    val cancelNote: String,
    val couponCode: String,
    val note: String,
    val customerUid: String,
    val customerName: String,
    val phone: String,
    val addressTitle: String,
    val addressDesc: String,
    val addressNote: String,
    val customerLat: Double,
    val customerLng: Double,
    val distanceKm: Double,
    val lines: List<VendorOrderLine>,
    val subtotalPaisa: Long,
    val deliveryPaisa: Long,
    val discountPaisa: Long,
    val totalAmountPaisa: Long
) {

    val itemCount: Int get() = lines.sumOf { it.qty }

    val isOpen: Boolean get() = OrderStateMachine.isActive(status)

    val isWaiting: Boolean get() = status == OrderStatus.PLACED

    val statusLabel: String get() = OrderStateMachine.label(status)

    val linesSummary: String
        get() = lines.joinToString(", ") { line -> "${line.qty} × ${line.name}" }

    val addressLine: String
        get() = listOf(addressTitle, addressDesc).filter { it.isNotBlank() }.joinToString(" · ")

    val etaLine: String
        get() = if (prepEndsAtMillis != null) {
            val minutes = ((prepEndsAtMillis - System.currentTimeMillis()) / 60_000L).toInt()
            if (minutes >= 0) "about $minutes min left" else "promised time passed"
        } else {
            "no promise yet"
        }

    /** Seconds since the order arrived — the queue shows it ticking (V-015). */
    fun ageLabel(nowMillis: Long = System.currentTimeMillis()): String {
        val seconds = ((nowMillis - createdAtMillis).coerceAtLeast(0L)) / 1000L
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
            else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
        }
    }

    /** 0 calm · 1 watched · 2 late · 3 very late — drives the age ramp (V-042). */
    fun ageLevel(nowMillis: Long = System.currentTimeMillis()): Int {
        if (!isOpen || status == OrderStatus.DELIVERED || status == OrderStatus.CANCELLED) return 0
        val minutes = (nowMillis - createdAtMillis).coerceAtLeast(0L) / 60_000L
        return when {
            minutes < 5 -> 0
            minutes < 15 -> 1
            minutes < 30 -> 2
            else -> 3
        }
    }

    /** True when the promised prep time has passed and the food is still cooking (V-035). */
    fun isOverdue(nowMillis: Long = System.currentTimeMillis()): Boolean =
        status == OrderStatus.ACCEPTED && prepEndsAtMillis != null && nowMillis > prepEndsAtMillis

    val timeline: List<VendorTimelineStep>
        get() {
            val steps = mutableListOf(
                VendorTimelineStep(
                    status = OrderStatus.PLACED,
                    label = "Placed by the customer",
                    atMillis = createdAtMillis.takeIf { it > 0L },
                    actor = OrderActor.CUSTOMER
                ),
                VendorTimelineStep(OrderStatus.ACCEPTED, "Accepted", acceptedAtMillis, OrderActor.KITCHEN),
                VendorTimelineStep(OrderStatus.READY, "Ready", readyAtMillis, OrderActor.KITCHEN),
                VendorTimelineStep(OrderStatus.IN_DELIVERY, "On the way", inDeliveryAtMillis, OrderActor.KITCHEN),
                VendorTimelineStep(OrderStatus.DELIVERED, "Delivered", deliveredAtMillis, OrderActor.KITCHEN)
            )
            if (status == OrderStatus.CANCELLED) {
                steps.add(
                    VendorTimelineStep(
                        status = OrderStatus.CANCELLED,
                        label = if (cancelNote.isBlank()) "Cancelled by the kitchen" else "Cancelled: $cancelNote",
                        atMillis = updatedAtMillis.takeIf { it > 0L },
                        actor = OrderActor.KITCHEN
                    )
                )
            }
            return steps
        }

    /** The text the share sheet sends out (V-040). */
    fun shareText(): String = buildString {
        append("Undal order ").append(shortId.ifBlank { id }).append('\n')
        lines.forEach { line -> append(line.qty).append(" × ").append(line.name).append('\n') }
        append("Total ").append(Money.format(totalAmountPaisa)).append(" · cash on delivery\n")
        if (addressLine.isNotBlank()) append(addressLine).append('\n')
        if (distanceKm > 0.0) append("${"%.1f".format(distanceKm)} km from the kitchen\n")
        append("Placed ").append(TimeDhaka.formatInstant(java.time.Instant.ofEpochMilli(createdAtMillis)))
    }

    companion object {

        const val CSV_HEADER =
            "short_id,placed_at,status,customer,phone,address,items,total_paisa,collected_paisa"

        fun fromDocument(id: String, map: Map<String, Any?>): VendorOrder? {
            val status = OrderStatus.fromWire(map["status"] as? String) ?: return null
            @Suppress("UNCHECKED_CAST")
            val address = map["address"] as? Map<String, Any?>
            val lines = (map["items"] as? List<*>)?.mapNotNull { entry ->
                val line = entry as? Map<*, *> ?: return@mapNotNull null
                val name = line["name"] as? String ?: return@mapNotNull null
                VendorOrderLine(
                    name = name,
                    qty = (line["qty"] as? Number)?.toInt() ?: 1,
                    unitAmountPaisa = (line["unitAmountPaisa"] as? Number)?.toLong() ?: 0L,
                    note = (line["note"] as? String).orEmpty()
                )
            }.orEmpty()

            return VendorOrder(
                id = id,
                shortId = (map["shortId"] as? String).orEmpty(),
                status = status,
                createdAtMillis = (map["createdAt"] as? Timestamp)?.toDate()?.time ?: 0L,
                updatedAtMillis = (map["updatedAt"] as? Timestamp)?.toDate()?.time ?: 0L,
                prepMinutes = (map["prepMinutes"] as? Number)?.toInt() ?: 0,
                prepEndsAtMillis = (map["prepEndsAt"] as? Timestamp)?.toDate()?.time,
                acceptedAtMillis = (map["acceptedAt"] as? Timestamp)?.toDate()?.time,
                readyAtMillis = (map["readyAt"] as? Timestamp)?.toDate()?.time,
                inDeliveryAtMillis = (map["inDeliveryAt"] as? Timestamp)?.toDate()?.time,
                deliveredAtMillis = (map["deliveredAt"] as? Timestamp)?.toDate()?.time,
                collectedAmountPaisa = (map["collectedAmountPaisa"] as? Number)?.toLong(),
                cancelNote = (map["cancelNote"] as? String).orEmpty(),
                couponCode = (map["couponCode"] as? String).orEmpty(),
                note = (map["note"] as? String).orEmpty(),
                customerUid = (map["customerId"] as? String).orEmpty(),
                customerName = (map["customerName"] as? String).orEmpty(),
                phone = (map["phone"] as? String).orEmpty(),
                addressTitle = (address?.get("title") as? String).orEmpty(),
                addressDesc = (address?.get("desc") as? String).orEmpty(),
                addressNote = (address?.get("note") as? String).orEmpty(),
                customerLat = (address?.get("lat") as? Number)?.toDouble() ?: 0.0,
                customerLng = (address?.get("lng") as? Number)?.toDouble() ?: 0.0,
                distanceKm = (map["distanceKm"] as? Number)?.toDouble() ?: 0.0,
                lines = lines,
                subtotalPaisa = (map["subtotalAmountPaisa"] as? Number)?.toLong() ?: 0L,
                deliveryPaisa = (map["deliveryAmountPaisa"] as? Number)?.toLong() ?: 0L,
                discountPaisa = (map["discountAmountPaisa"] as? Number)?.toLong() ?: 0L,
                totalAmountPaisa = (map["totalAmountPaisa"] as? Number)?.toLong() ?: 0L
            )
        }

        /** Today, in Dhaka terms: orders from midnight Asia/Dhaka onward. */
        fun todayStartMillis(): Long =
            TimeDhaka.today().atStartOfDay(TimeDhaka.zone).toInstant().toEpochMilli()

        /** The bar chart series: one point per day, oldest first (V-014). */
        fun weekSeries(orders: List<VendorOrder>, days: Int = 7): List<VendorDayPoint> {
            val today = TimeDhaka.today()
            return (days - 1 downTo 0).map { offset ->
                val date = today.minusDays(offset.toLong())
                val dayOrders = orders.filter { order ->
                    TimeDhaka.fromInstant(java.time.Instant.ofEpochMilli(order.createdAtMillis)).toLocalDate() == date
                }
                VendorDayPoint(
                    dayKey = date.toString(),
                    label = TimeDhaka.weekdayLabel(date).take(3),
                    orders = dayOrders.size,
                    revenuePaisa = dayOrders
                        .filter { it.status == OrderStatus.DELIVERED }
                        .sumOf { it.collectedAmountPaisa ?: it.totalAmountPaisa }
                )
            }
        }

        /** Today's events, newest first (V-021). */
        fun events(orders: List<VendorOrder>, limit: Int = 12): List<VendorStatusEvent> {
            val events = mutableListOf<VendorStatusEvent>()
            orders.forEach { order ->
                if (order.createdAtMillis > 0L) {
                    events.add(
                        VendorStatusEvent(
                            orderId = order.id,
                            shortId = order.shortId,
                            atMillis = order.createdAtMillis,
                            text = "${order.shortId} arrived · ${Money.format(order.totalAmountPaisa)}"
                        )
                    )
                }
                val stamps = listOf(
                    Triple(order.acceptedAtMillis, "accepted", "Accepted"),
                    Triple(order.readyAtMillis, "ready", "Ready"),
                    Triple(order.inDeliveryAtMillis, "out", "Left the kitchen"),
                    Triple(order.deliveredAtMillis, "delivered", "Delivered")
                )
                stamps.forEach { (millis, _, label) ->
                    if (millis != null && millis > 0L) {
                        events.add(
                            VendorStatusEvent(
                                orderId = order.id,
                                shortId = order.shortId,
                                atMillis = millis,
                                text = "${order.shortId} $label"
                            )
                        )
                    }
                }
                if (order.status == OrderStatus.CANCELLED && order.updatedAtMillis > 0L) {
                    events.add(
                        VendorStatusEvent(
                            orderId = order.id,
                            shortId = order.shortId,
                            atMillis = order.updatedAtMillis,
                            text = "${order.shortId} cancelled — ${order.cancelNote}"
                        )
                    )
                }
            }
            return events.sortedByDescending { it.atMillis }.take(limit)
        }

        /** The CSV the owner shares at closing time (V-094). */
        fun csv(orders: List<VendorOrder>): String {
            val rows = orders.sortedBy { it.createdAtMillis }.joinToString("\n") { order ->
                listOf(
                    order.shortId.ifBlank { order.id },
                    TimeDhaka.formatInstant(java.time.Instant.ofEpochMilli(order.createdAtMillis)),
                    order.statusLabel,
                    order.customerName,
                    order.phone,
                    order.addressLine,
                    order.linesSummary,
                    order.totalAmountPaisa.toString(),
                    (order.collectedAmountPaisa ?: 0L).toString()
                ).joinToString(",") { cell -> csvCell(cell) }
            }
            return CSV_HEADER + "\n" + rows
        }

        private fun csvCell(value: String): String =
            if (value.contains(',') || value.contains('"') || value.contains('\n')) {
                "\"" + value.replace("\"", "\"\"") + "\""
            } else {
                value
            }
    }
}
