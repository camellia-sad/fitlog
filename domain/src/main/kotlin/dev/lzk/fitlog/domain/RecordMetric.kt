package dev.lzk.fitlog.domain

/** The legacy reps field stores the quantity in the parent exercise's snapshotted unit. */
fun SetRecord.displayValue(metric:RecordMetric,unit:WeightUnit=WeightUnit.KG):String =
    if(metric==RecordMetric.SECONDS) "$reps 秒"
    else WeightUnits.format(weightGrams,unit)+" "+unit.label+" × "+reps
