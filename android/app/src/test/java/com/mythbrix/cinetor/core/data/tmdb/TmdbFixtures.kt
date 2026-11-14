package com.mythbrix.cinetor.core.data.tmdb

import java.io.IOException

/** Records every requested URL; answers with [body] (or throws when [fail]). No network. */
class FakeTmdbHttp(var body: String = TmdbFixtures.PAGE, var fail: Boolean = false) : TmdbHttp {
    val urls = mutableListOf<String>()
    override suspend fun get(url: String): String {
        urls += url
        if (fail) throw IOException("offline")
        return body
    }
}

object TmdbFixtures {
    const val PAGE = """
{"page":1,"total_pages":1,"results":[
 {"id":550,"media_type":"movie","title":"Fight Club","original_title":"Fight Club","overview":"An insomniac.",
  "poster_path":"/p550.jpg","backdrop_path":"/b550.jpg","genre_ids":[18,53],"release_date":"1999-10-15",
  "vote_average":8.43,"adult":false,"original_language":"en"},
 {"id":1396,"media_type":"tv","name":"Breaking Bad","original_name":"Breaking Bad","overview":"A teacher.",
  "poster_path":"/p1396.jpg","backdrop_path":"/b1396.jpg","genre_ids":[18,80],"first_air_date":"2008-01-20",
  "vote_average":8.9,"adult":false,"origin_country":["US"],"original_language":"en"}
]}"""

    const val SEARCH = """
{"page":1,"results":[
 {"id":1,"media_type":"movie","title":"Safe Movie","genre_ids":[28],"release_date":"2020-01-01","adult":false},
 {"id":2,"media_type":"movie","title":"Adult Movie","genre_ids":[18],"release_date":"2020-01-01","adult":true},
 {"id":3,"media_type":"person","name":"Some Person","adult":false},
 {"id":4,"media_type":"tv","name":"Squid Game","genre_ids":[18],"origin_country":["KR"],"original_language":"ko","adult":false},
 {"id":5,"media_type":"tv","name":"Frieren","genre_ids":[16,10765],"origin_country":["JP"],"original_language":"ja","adult":false},
 {"id":6,"media_type":"tv","name":"Adult Show","genre_ids":[18],"adult":true}
]}"""
}
