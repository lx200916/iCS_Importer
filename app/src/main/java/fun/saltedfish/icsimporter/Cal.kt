package `fun`.saltedfish.icsimporter

data class Cal(val name:String,val owner:String,val id:Long){
    override fun toString(): String {
        return "$name($owner)"
    }
}
