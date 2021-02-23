package `fun`.saltedfish.icsimporter


import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.*
import android.database.Cursor
import android.net.MailTo
import android.net.ParseException
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.provider.CalendarContract
import android.text.TextUtils
import android.text.format.DateUtils
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.permissionx.guolindev.PermissionX
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.material_custom_spinner.*
import kotlinx.android.synthetic.main.material_custom_spinner.view.*
import kotlinx.coroutines.*
import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.model.*
import net.fortuna.ical4j.model.Calendar
import net.fortuna.ical4j.model.component.CalendarComponent
import net.fortuna.ical4j.model.component.VAlarm
import net.fortuna.ical4j.model.component.VEvent
import net.fortuna.ical4j.model.parameter.FbType
import net.fortuna.ical4j.model.parameter.Related
import net.fortuna.ical4j.model.property.*
import provider.CalendarContractWrapper
import java.io.InputStream
import java.time.temporal.ChronoUnit
import java.util.*
import kotlin.time.microseconds

private val EVENT_PROJECTION: Array<String> = arrayOf(
    CalendarContract.Calendars._ID,                     // 0
    CalendarContract.Calendars.ACCOUNT_NAME,            // 1
    CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,   // 2
    CalendarContract.Calendars.OWNER_ACCOUNT,
    CalendarContract.Calendars.NAME // 3
)


private val EVENT_QUERY_COLUMNS =
    arrayOf(CalendarContractWrapper.Events.CALENDAR_ID, CalendarContractWrapper.Events._ID)
private const val EVENT_QUERY_CALENDAR_ID_COL = 0
private const val EVENT_QUERY_ID_COL = 1
private const val TAG = "ICS_ProcessVEvent"

// The indices for the projection array above.
private const val PROJECTION_ID_INDEX: Int = 0
private const val PROJECTION_ACCOUNT_NAME_INDEX: Int = 1
private const val PROJECTION_DISPLAY_NAME_INDEX: Int = 2
private const val PROJECTION_OWNER_ACCOUNT_INDEX: Int = 3

class MainActivity : AppCompatActivity(), CoroutineScope by MainScope() {
    private val mCalendarBuilder: CalendarBuilder by lazy {
        CalendarBuilder()
    }
    private lateinit var mCalendar: net.fortuna.ical4j.model.Calendar
    private val ONE_DAY = createDuration("P1D")
    private val ZERO_SECONDS = createDuration("PT0S")
    private lateinit var adapter: ArrayAdapter<Cal>
    private var calId = -1L
    private var Duplicate_Replace = false
    var mUidMs = 0L
    val msharedpreferences by lazy {
        getSharedPreferences("ICSImporter", MODE_PRIVATE)
    }
    var mUidTail = ""
    private var isInsert = true
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

//        setSupportActionBar(findViewById(R.id.toolbar))
        PermissionX.init(this)
            .permissions(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
            .explainReasonBeforeRequest()
            .onExplainRequestReason { scope, deniedList ->
                scope.showRequestReasonDialog(
                    deniedList,
                    "需要权限读取日历列表/写入日程,否则程序无法工作.",
                    "OK",
                    "Cancel"
                )
            }

            .request { allGranted, grantedList, deniedList ->
                if (allGranted) {

                    getCalendar()
                    Toast.makeText(this, "All permissions are granted", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(
                        this,
                        "These permissions are denied: $deniedList",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

        mUidTail = msharedpreferences.getString("UUID_pre", "") ?: ""
//        (Calender_name.editText as? AutoCompleteTextView)?.
        planets_spinner.setLabel("选择日历")
        adapter = ArrayAdapter(
            this@MainActivity, R.layout.list_item, mutableListOf(
                Cal(
                    "新建日历",
                    "本机",
                    -1
                )
            )
        )
        planets_spinner.setItemSelectedListener(object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, p2: Int, p3: Long) {
//                TODO("Not yet implemented")
                if (p2 == 0) {
                    CaltextField.visibility = View.VISIBLE
                    outlinedButton_del.visibility = View.GONE
                    return
                } else {
                    CaltextField.visibility = View.GONE
                    outlinedButton_del.visibility = View.VISIBLE

                    calId = adapter.getItem(p2)?.id ?: -1L

                }

            }

            override fun onNothingSelected(p0: AdapterView<*>?) {
//                TODO("Not yet implemented")
                planets_spinner.setError("请选择目标日历")

            }

        })
        adapter.setDropDownViewResource(R.layout.list_item)
        planets_spinner.getSpinner().adapter = adapter
        main_card_status.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.type = "text/calendar"
//            intent.type=""
            startActivityForResult(intent, 1)
        }
        main_card_status.setOnLongClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.type = "text/calendar"
//            intent.type=""
            startActivityForResult(intent, 1)
            return@setOnLongClickListener true
        }
        outlinedButton.setOnClickListener {
            if (calId == -1L) {
                val name = CaltextField.editText?.text?.toString()
                if (name.isNullOrEmpty()) {
                    CaltextField.error = "请输入名称"
                    return@setOnClickListener
                }
                calId = newCalendar(name)
                if (calId == -1L) {
                    MaterialAlertDialogBuilder(this).setTitle(resources.getString(R.string.cal_create_error_title))
                        .setMessage(resources.getString(R.string.cal_create_error_msg))
                        .setPositiveButton(
                            "OK"
                        ) { dialog, which ->
                            dialog.cancel()
                        }
                        .show()
                    return@setOnClickListener
                } else {
                    isInsert = true
                    runInsert()
                }


            } else {
                isInsert = true
                runInsert()
            }
        }
        outlinedButton_del.setOnClickListener {
            if (calId == -1L) {
//                val name = CaltextField.editText?.text?.toString()
//                if (name.isNullOrEmpty()) {
//                    CaltextField.error = "请输入名称"
//                    return@setOnClickListener
//                }
//                calId = newCalendar(name)
//                if (calId == -1L) {
//                    MaterialAlertDialogBuilder(this).setTitle(resources.getString(R.string.cal_create_error_title))
//                        .setMessage(resources.getString(R.string.cal_create_error_msg))
//                        .setPositiveButton(
//                            "OK"
//                        ) { dialog, which ->
//                            dialog.cancel()
//                        }
//                        .show()
//                    return@setOnClickListener
//                } else {
//                    isInsert=false
//                    runInsert()
//                }


            } else {
                isInsert = false
                runInsert()
            }
        }
        choice_1.setOnCheckedChangeListener { group, checkedId ->
            Duplicate_Replace = checkedId == R.id.dup_2
        }
        val action = intent.action
        if (Intent.ACTION_VIEW == action) {
            val uri: Uri = intent.data ?: return
            buildiCS(it = uri)

        }
        if (Intent.ACTION_SEND == action) {
            (intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri)?.let {
                buildiCS(it)

            }
        }
//        findViewById<FloatingActionButton>(R.id.fab).setOnClickListener { view ->
//
//
//        }
    }

    private fun queryEvents(
        resolver: ContentResolver,
        b: StringBuilder,
        argsList: List<String>
    ): Cursor? {
        val where = b.toString()
        val args = argsList.toTypedArray()
        return resolver.query(
            CalendarContractWrapper.Events.CONTENT_URI,
            EVENT_QUERY_COLUMNS,
            where,
            args,
            null
        )
    }

    private fun query(
        resolver: ContentResolver,

        c: ContentValues
    ): Cursor? {
        val b = StringBuilder()
        val argsList: MutableList<String> = ArrayList()
        if (CalendarContractWrapper.Events.UID_2445 != null && c.containsKey(
                CalendarContractWrapper.Events.UID_2445
            )
        ) {
            // Use our UID to query, either globally or per-calendar unique

            b.append(CalendarContractWrapper.Events.CALENDAR_ID).append("=? AND ")
            argsList.add(c.getAsString(CalendarContractWrapper.Events.CALENDAR_ID))

            b.append(CalendarContractWrapper.Events.UID_2445).append("=?")
            argsList.add(c.getAsString(CalendarContractWrapper.Events.UID_2445))
            return queryEvents(resolver, b, argsList)
        }

        // Without UIDs, the best we can do is check the start date and title within
        // the current calendar, even though this may return false duplicates.
        if (!c.containsKey(CalendarContractWrapper.Events.CALENDAR_ID) || !c.containsKey(
                CalendarContractWrapper.Events.DTSTART
            )
        ) return null
        b.append(CalendarContractWrapper.Events.CALENDAR_ID).append("=? AND ")
        b.append(CalendarContractWrapper.Events.DTSTART).append("=? AND ")
        b.append(CalendarContractWrapper.Events.TITLE)
        argsList.add(c.getAsString(CalendarContractWrapper.Events.CALENDAR_ID))
        argsList.add(c.getAsString(CalendarContractWrapper.Events.DTSTART))
        if (c.containsKey(CalendarContractWrapper.Events.TITLE)) {
            b.append("=?")
            argsList.add(c.getAsString(CalendarContractWrapper.Events.TITLE))
        } else b.append(" is null")
        return queryEvents(resolver, b, argsList)
    }

    fun getCalendar() {
        val queryHandler = object : AsyncQueryHandler(contentResolver) {
            @SuppressLint("HandlerLeak")
            override fun onQueryComplete(token: Int, cookie: Any?, cursor: Cursor?) {
                if (token == 1) {
                    if (cursor == null || cursor.count == 0) {
                        return
                    }
                    val lists: MutableList<Cal> = mutableListOf()
                    while (cursor.moveToNext()) {
                        print(cursor)
                        print(cursor.getString(4))
//                        Toast.makeText(this@MainActivity, cursor.getString(PROJECTION_DISPLAY_NAME_INDEX), Toast.LENGTH_SHORT).show()
//                       Log.d("Owner",cursor.getString(PROJECTION_ACCOUNT_NAME_INDEX))
//                        Log.d("ID",cursor.getLong(PROJECTION_ID_INDEX).toString())
//                        Log.d("Name",cursor.getString(PROJECTION_DISPLAY_NAME_INDEX))
                        //                        print(cursor.getString(PROJECTION_DISPLAY_NAME_INDEX))
//                        print(cursor.getLong(PROJECTION_ID_INDEX))
                        lists.add(
                            Cal(
                                cursor.getString(PROJECTION_DISPLAY_NAME_INDEX), cursor.getString(
                                    PROJECTION_ACCOUNT_NAME_INDEX
                                ), cursor.getLong(PROJECTION_ID_INDEX)
                            )
                        )


                    }
                    adapter.addAll(lists)


                }
            }
        }
        queryHandler.startQuery(
            1,
            null,
            CalendarContract.Calendars.CONTENT_URI,
            EVENT_PROJECTION,
            null,
            null,
            null
        )

    }

    fun runInsert() {
        running_view.visibility = View.VISIBLE
        card_options.visibility = View.GONE
        running_title.text = "正在导入"
        launch {
            var numDups = 0
            var numDel = 0
            var numIns = 0
            val events: ComponentList<VEvent> =
                mCalendar.getComponents<VEvent>(VEvent.VEVENT)
            val cAlarm = ContentValues()
            val reminders: MutableList<Int> = ArrayList()
            cAlarm.put(
                CalendarContractWrapper.Reminders.METHOD,
                CalendarContractWrapper.Reminders.METHOD_ALERT
            )
            for (event in events) {
                run_progress.incrementProgressBy(1)
                val e = event as VEvent

                val c = convertToDB(e, reminders, calId)
                var cur: Cursor? = null
                var mustDelete = isInsert.not()
                if (!mustDelete) {
                    cur = query(contentResolver, c)
                    while (isInsert && cur != null && cur.moveToNext()) {
                        if (Duplicate_Replace)
                            mustDelete = cur.getLong(EVENT_QUERY_CALENDAR_ID_COL) == calId
                        else {
                            mustDelete = true
                        }
                    }

                    if (mustDelete) {
                        numDups++
                        if (!Duplicate_Replace) {
                            Log.i(TAG, "Avoiding inserting a duplicate event")

                            cur?.close()
                            continue
                        }
                        cur?.moveToPosition(-1) // Rewind for use below
                    }
                }
                if (mustDelete) {
                    if (cur == null) cur = query(contentResolver, c)
                    while (cur != null && cur.moveToNext()) {
                        val rowCalendarId =
                            cur.getLong(EVENT_QUERY_CALENDAR_ID_COL)
                        if ((Duplicate_Replace || !isInsert)
                            && rowCalendarId != calId
                        ) {
                            Log.i(
                                TAG,
                                "Avoiding deleting duplicate event in calendar $rowCalendarId"
                            )
                            continue  // Not in the destination calendar
                        }
                        val id =
                            cur.getString(EVENT_QUERY_ID_COL)
                        val eventUri = Uri.withAppendedPath(
                            CalendarContractWrapper.Events.CONTENT_URI,
                            id
                        )
                        numDel += contentResolver.delete(eventUri, null, null)
                        val where: String =
                            CalendarContractWrapper.Reminders.EVENT_ID.toString() + "=?"
                        contentResolver.delete(
                            CalendarContractWrapper.Reminders.CONTENT_URI, where, arrayOf(
                                id
                            )
                        )

                    }
                }
                cur?.close()
                if (isInsert.not()) continue
                if (CalendarContractWrapper.Events.UID_2445 != null && !c.containsKey(
                        CalendarContractWrapper.Events.UID_2445
                    )
                ) {
                    // Create a UID for this event to use. We create it here so if
                    // exported multiple times it will always have the same id.
                    c.put(CalendarContractWrapper.Events.UID_2445, generateUid())
                }

                c.put(CalendarContractWrapper.Events.CALENDAR_ID, calId)


                val uri: Uri = insertAndLog(
                    contentResolver,
                    CalendarContractWrapper.Events.CONTENT_URI,
                    c,
                    "Event"
                )
                    ?: continue

                val id = uri.lastPathSegment!!.toLong()

                for (time in reminders) {
                    cAlarm.put(CalendarContractWrapper.Reminders.EVENT_ID, id)
                    cAlarm.put(CalendarContractWrapper.Reminders.MINUTES, time)
                    insertAndLog(
                        contentResolver,
                        CalendarContractWrapper.Reminders.CONTENT_URI,
                        cAlarm,
                        "Reminder"
                    )
                }
                numIns++


            }
            running_title.text = "导入成功"

            add_count.visibility = View.VISIBLE
            if (isInsert) {
                dup_count.visibility = View.VISIBLE
            }
            add_count.text = if (isInsert) "已增加 %d 项".format(numIns) else "已删除 %d 项".format(numDel)
            dup_count.text = "有 %d 项重复".format(numDups)

        }
    }

    private fun insertAndLog(
        resolver: ContentResolver,
        uri: Uri,
        c: ContentValues,
        type: String
    ): Uri? {

        Log.d(TAG, "Inserting " + type + " values: " + c)
        val result = resolver.insert(uri, c)
        if (result == null) {
            Log.e(TAG, "failed to insert " + type)

            Log.e(TAG, "failed " + type + " values: " + c) // Not already logged, dump now
        } else
            Log.d(TAG, "Insert " + type + " returned " + result.toString())
        return result
    }

    fun generateUid(): String {

        if (mUidTail.isNullOrEmpty()) {
            val uidPid = UUID.randomUUID().toString().replace("-", "")
            mUidTail = "$uidPid@saltedfish.fun"
        }
        if (mUidMs == 0L) {
            mUidMs = mUidMs.coerceAtLeast(System.currentTimeMillis())
        }
        val uid = mUidMs.toString() + mUidTail
        mUidMs++
        return uid
    }

    fun buildiCS(it: Uri) {
        val file: InputStream?
        try {
            if (it.scheme == "file") {
                MaterialAlertDialogBuilder(this).setTitle("使用了File:// URI")
                    .setMessage("获取到了File:// URI,这项特性已经出于安全性考量在Android 6.0时代废除.\n读取File:// URI需要获取外置存储卡的完全访问权限,这违反了 Android 隐私管理思想,因此本程序不做适配.\n请更换打开/分享请求的发起应用.这些应用多数已经过时，靠低API兼容性苟延残喘，如Flyme 文件管理.")
                    .setPositiveButton("OK") { dialog, which ->
                        dialog.cancel()

                    }.show()

            }
            file = contentResolver.openInputStream(it)
            val document = DocumentFile.fromSingleUri(
                this,
                it
            )

            if (file == null || document == null || document.isDirectory || document.length() == 0L) {
                Toast.makeText(this, "文件打开错误", Toast.LENGTH_LONG).show()
                return
            }


            main_card_status_title.text =
                document.name ?: ""
            main_card_status_indicator.text = "%.2f KB".format(
                document.length()
                    .div(1024.0)
                    ?: ""
            )
        } catch (e: java.lang.Exception) {
            Toast.makeText(this, "文件打开错误", Toast.LENGTH_LONG).show()
            return

        }
        try {
            card_main_op.visibility = View.VISIBLE
            running_title.text = "正在解析"
            count_text.text = ""
            card_options.visibility = View.GONE
            run_progress.visibility = View.VISIBLE
            run_progress.isIndeterminate = true
            running_view.visibility = View.VISIBLE


            launch {
                mCalendar = get(file)
                val n =
                    mCalendar.getComponents<CalendarComponent>(VEvent.VEVENT).size
                running_title.text = "导入设置"

                count_text.text = "共 %d 条数据".format(n)
                run_progress.isIndeterminate = false
                run_progress.setProgress(0, true)
                run_progress.max = n
                running_view.visibility = View.GONE
                card_options.visibility = View.VISIBLE
            }


        } catch (e: Exception) {
            e.printStackTrace()
        }


    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            1 -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    data.data?.let {
//it.toFile().name


                        buildiCS(it)


                    }
                }
            }
        }
    }

    suspend fun get(ins: InputStream): Calendar = withContext(Dispatchers.IO) {
        return@withContext mCalendarBuilder.build(ins)
    }

    override fun onDestroy() {
        cancel()
        super.onDestroy()

    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    private fun createDuration(value: String): Duration {
        val d = Duration()
        d.value = value
        return d
    }

    private fun durationToMs(d: Dur): Long {
        var ms: Long = 0
        ms += d.seconds * DateUtils.SECOND_IN_MILLIS
        ms += d.minutes * DateUtils.MINUTE_IN_MILLIS
        ms += d.hours * DateUtils.HOUR_IN_MILLIS
        ms += d.days * DateUtils.DAY_IN_MILLIS
        ms += d.weeks * DateUtils.WEEK_IN_MILLIS
        return ms
    }

    private fun hasProperty(e: VEvent, name: String): Boolean {
        return e.getProperty<Property>(name) != null
    }

    private fun removeProperty(e: VEvent, name: String) {
        val p = e.getProperty<Property>(name)
        if (p != null) e.properties.remove(p)
    }

    private fun copyProperty(c: ContentValues, dbName: String?, e: VEvent, evName: String) {
        if (dbName != null) {
            val p = e.getProperty<Property>(evName)
            if (p != null) c.put(dbName, p.value)
        }
    }

    private fun copyDateProperty(
        c: ContentValues,
        dbName: String?,
        dbTzName: String?,
        date: DateProperty
    ) {
        if (dbName != null && date.date != null) {
            c.put(dbName, date.date.time) // ms since epoc in GMT
            if (dbTzName != null) {
                if (date.isUtc) c.put(dbTzName, "UTC") else if (date.timeZone == null) {
                    c.put(
                        dbTzName,
                        "Asia/Shanghai"
                    )
                } else c.put(
                    dbTzName,
                    date.timeZone.id
                )
            }
        }
    }

    private fun newCalendar(Name: String): Long {
        var calUri: Uri = CalendarContract.Calendars.CONTENT_URI
        val cv = ContentValues()
        cv.put(CalendarContract.Calendars.ACCOUNT_NAME, "ICS_Importer")
        cv.put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
        cv.put(CalendarContract.Calendars.NAME, Name)
        cv.put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, Name)
        cv.put(
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.CAL_ACCESS_OWNER
        )
        cv.put(CalendarContract.Calendars.OWNER_ACCOUNT, true)
        cv.put(CalendarContract.Calendars.VISIBLE, 1)
        cv.put(CalendarContract.Calendars.SYNC_EVENTS, 1)

        calUri = calUri.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, "ICS_Importer")
            .appendQueryParameter(
                CalendarContract.Calendars.ACCOUNT_TYPE,
                CalendarContract.ACCOUNT_TYPE_LOCAL
            )
            .build()
        val result: Uri? = this.contentResolver.insert(calUri, cv)
        print(result)
        return result?.lastPathSegment?.toLong() ?: -1


    }

    suspend fun convertToDB(
        e: VEvent,
        reminders: MutableList<Int>, calendarId: Long
    ): ContentValues {
        reminders.clear()
        var allDay = false
        val startIsDate = e.startDate.date !is DateTime
        val isRecurring = hasProperty(e, Property.RRULE) || hasProperty(e, Property.RDATE)
        if (startIsDate) {
            // If the start date is a DATE we expect the end date to be a date too and the
            // event is all-day, midnight to midnight (RFC 2445).
            allDay = true
        }
        if (!hasProperty(e, Property.DTEND) && !hasProperty(e, Property.DURATION)) {
            // No end date or duration given.
            // Since we added a duration above when the start date is a DATE:
            // - The start date is a DATETIME, the event lasts no time at all (RFC 2445).
            e.properties.add(ZERO_SECONDS)
            // Zero time events are always free (RFC 2445), so override/set TRANSP accordingly.
            removeProperty(e, Property.TRANSP)
            e.properties.add(Transp.TRANSPARENT)
        }
        if (isRecurring) {
            // Recurring event. Android insists on a duration.
            if (!hasProperty(e, Property.DURATION)) {
                // Calculate duration from start to end date
                val d = Duration(e.startDate.date, e.endDate.date)
                e.properties.add(d)
            }
            removeProperty(e, Property.DTEND)
        } else {
            // Non-recurring event. Android insists on an end date.
            if (!hasProperty(e, Property.DTEND)) {
                // Calculate end date from duration, set it and remove the duration.
                e.properties.add(e.endDate)
            }
            removeProperty(e, Property.DURATION)
        }

        // Now calculate the db values for the event
        val c = ContentValues()
        c.put(CalendarContractWrapper.Events.CALENDAR_ID, calendarId)
        copyProperty(c, CalendarContractWrapper.Events.TITLE, e, Property.SUMMARY)
        copyProperty(c, CalendarContractWrapper.Events.DESCRIPTION, e, Property.DESCRIPTION)
        if (e.organizer != null) {
            val uri = e.organizer.calAddress
            try {
                val mailTo = MailTo.parse(uri.toString())
                c.put(CalendarContractWrapper.Events.ORGANIZER, mailTo.to)
                c.put(
                    CalendarContractWrapper.Events.GUESTS_CAN_MODIFY,
                    1
                ) // Ensure we can edit if not the organiser
            } catch (ignored: ParseException) {
                Log.e(TAG, "Failed to parse Organiser URI $uri")
            }
        }
        copyProperty(c, CalendarContractWrapper.Events.EVENT_LOCATION, e, Property.LOCATION)
        if (hasProperty(e, Property.STATUS)) {
            val status = e.getProperty<Property>(Property.STATUS).value
            when (status) {
                "TENTATIVE" -> c.put(
                    CalendarContractWrapper.Events.STATUS,
                    CalendarContractWrapper.Events.STATUS_TENTATIVE
                )
                "CONFIRMED" -> c.put(
                    CalendarContractWrapper.Events.STATUS,
                    CalendarContractWrapper.Events.STATUS_CONFIRMED
                )
                "CANCELLED" -> c.put(
                    CalendarContractWrapper.Events.STATUS,
                    CalendarContractWrapper.Events.STATUS_CANCELED
                )
            }
        }
        copyProperty(c, CalendarContractWrapper.Events.DURATION, e, Property.DURATION)
        if (allDay) c.put(CalendarContractWrapper.Events.ALL_DAY, 1)
        copyDateProperty(
            c,
            CalendarContractWrapper.Events.DTSTART,
            CalendarContractWrapper.Events.EVENT_TIMEZONE,
            e.startDate
        )
        if (hasProperty(e, Property.DTEND)) copyDateProperty(
            c,
            CalendarContractWrapper.Events.DTEND,
            CalendarContractWrapper.Events.EVENT_END_TIMEZONE,
            e.endDate
        )
        if (hasProperty(e, Property.CLASS)) {
            val access = e.getProperty<Property>(Property.CLASS).value
            var accessLevel = CalendarContractWrapper.Events.ACCESS_DEFAULT
            when (access) {
                "CONFIDENTIAL" -> accessLevel = CalendarContractWrapper.Events.ACCESS_CONFIDENTIAL
                "PRIVATE" -> accessLevel = CalendarContractWrapper.Events.ACCESS_PRIVATE
                "PUBLIC" -> accessLevel = CalendarContractWrapper.Events.ACCESS_PUBLIC
            }
            c.put(CalendarContractWrapper.Events.ACCESS_LEVEL, accessLevel)
        }

        // Work out availability. This is confusing as FREEBUSY and TRANSP overlap.
        if (CalendarContractWrapper.Events.AVAILABILITY != null) {
            var availability = CalendarContractWrapper.Events.AVAILABILITY_BUSY
            if (hasProperty(e, Property.TRANSP)) {
                if (e.transparency === Transp.TRANSPARENT) availability =
                    CalendarContractWrapper.Events.AVAILABILITY_FREE
            } else if (hasProperty(e, Property.FREEBUSY)) {
                val fb = e.getProperty(Property.FREEBUSY) as FreeBusy
                val fbType = fb.getParameter(Parameter.FBTYPE) as FbType
                if (fbType != null && fbType === FbType.FREE) availability =
                    CalendarContractWrapper.Events.AVAILABILITY_FREE else if (fbType != null && fbType === FbType.BUSY_TENTATIVE) availability =
                    CalendarContractWrapper.Events.AVAILABILITY_TENTATIVE
            }
            c.put(CalendarContractWrapper.Events.AVAILABILITY, availability)
        }
        copyProperty(c, CalendarContractWrapper.Events.RRULE, e, Property.RRULE)
        copyProperty(c, CalendarContractWrapper.Events.RDATE, e, Property.RDATE)
        copyProperty(c, CalendarContractWrapper.Events.EXRULE, e, Property.EXRULE)
        copyProperty(c, CalendarContractWrapper.Events.EXDATE, e, Property.EXDATE)
        copyProperty(c, CalendarContractWrapper.Events.CUSTOM_APP_URI, e, Property.URL)
        copyProperty(c, CalendarContractWrapper.Events.UID_2445, e, Property.UID)
        if (c.containsKey(CalendarContractWrapper.Events.UID_2445) && TextUtils.isEmpty(
                c.getAsString(
                    CalendarContractWrapper.Events.UID_2445
                )
            )
        ) {
            // Remove null/empty UIDs
            c.remove(CalendarContractWrapper.Events.UID_2445)
        }
        for (alarm in e.alarms) {
            val a = alarm as VAlarm
            if (a.action !== Action.AUDIO && a.action !== Action.DISPLAY) continue  // Ignore email and procedure alarms
            val t = a.trigger
            val startMs = e.startDate.date.time
            var alarmStartMs = startMs
            var alarmMs: Long

            // FIXME: - Support for repeating alarms
            //        - Check the calendars max number of alarms
            if (t.dateTime != null) alarmMs = t.dateTime.time // Absolute
            else if (t.duration != null && java.time.Duration.from(t.duration).isNegative) {
                t.duration
                val rel = t.getParameter(Parameter.RELATED) as? Related
                if (rel != null && rel === Related.END) alarmStartMs = e.endDate.date.time
                alarmMs = alarmStartMs - java.time.Duration.from(t.duration).toMillis()
            } else {
                continue
            }
            val reminder = ((startMs - alarmMs) / DateUtils.MINUTE_IN_MILLIS).toInt()
            if (reminder >= 0 && !reminders.contains(reminder)) reminders.add(reminder)
        }
        if (reminders.size > 0) c.put(CalendarContractWrapper.Events.HAS_ALARM, 1)

        // FIXME: Attendees, SELF_ATTENDEE_STATUS
        return c
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        when (item.itemId) {
            R.id.action_settings -> {
                PermissionX.init(this)
                    .permissions(
                        Manifest.permission.READ_CALENDAR,
                        Manifest.permission.WRITE_CALENDAR
                    )
                    .explainReasonBeforeRequest()
                    .onExplainRequestReason { scope, deniedList ->
                        scope.showRequestReasonDialog(
                            deniedList,
                            "需要权限读取日历列表/写入日程,否则程序无法工作.",
                            "OK",
                            "Cancel"
                        )
                    }
                    .request { allGranted, grantedList, deniedList ->
                        if (allGranted) {

                            getCalendar()
                            Toast.makeText(
                                this,
                                "All permissions are granted",
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            Toast.makeText(
                                this,
                                "These permissions are denied: $deniedList",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }

            }
            else -> super.onOptionsItemSelected(item)

        }
        return true
    }
}