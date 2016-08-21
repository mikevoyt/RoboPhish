package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io/ioutil"
	"net/http"
	"strconv"
	"time"
)

type Year struct {
	Date      string `json:"date"`
	ShowCount int64  `json:"show_count"`
}

type APIResponse struct {
	Years []Year `json:"data"`
}

type ShowData struct {
	Id    int64  `json:"id"`
	Date  string `json:"date"`
	Venue string `json:"venue_name"`
}

type Show struct {
	Data []ShowData `json:"data"`
}

type GcmData struct {
	Title    string `json:"title"`
	SubTitle string `json:"subtitle"`
	MediaId  string `json:"mediaid"`
}

type GcmMessage struct {
	To   string  `json:"to"`
	Data GcmData `json:"data"`
}

var mYears []Year

func main() {
	years, _ := getYears()
	mYears = years

	for _, year := range mYears {
		fmt.Println(year.Date)
		fmt.Println(year.ShowCount)
	}

	t := time.NewTicker(time.Minute * 30)
	for {
		checkForUpdate()
		<-t.C
	}
}

func checkForUpdate() {
	years, _ := getYears()

	if len(years) != len(mYears) {
		fmt.Println("We have a new year!")
		mYears = years
		shows := int(mYears[len(mYears)-1].ShowCount)
		getLatestShows(shows)
	} else {
		n := len(mYears) - 1
		if mYears[n].ShowCount != years[n].ShowCount {
			fmt.Printf("New show! Year=%v, old=%v, new=%v\n", years[n].Date,
				mYears[n].ShowCount, years[n].ShowCount)
			diff := int(years[n].ShowCount - mYears[n].ShowCount)
			mYears = years
			getLatestShows(diff)
		}
	}

}

func sendGsm(request []byte) {
	API_KEY := ""

	url := "https://android.googleapis.com/gcm/send"
	fmt.Println("URL:>", url)

	req, err := http.NewRequest("POST", url, bytes.NewBuffer(request))
	req.Header.Set("Authorization", "key="+API_KEY)
	req.Header.Set("Content-Type", "application/json")

	client := &http.Client{}
	resp, err := client.Do(req)
	if err != nil {
		panic(err)
	}
	defer resp.Body.Close()

	fmt.Println("response Status:", resp.Status)
	fmt.Println("response Headers:", resp.Header)
	body, _ := ioutil.ReadAll(resp.Body)
	fmt.Println("response Body:", string(body))
}

func getLatestShows(count int) {

	res, err := http.Get("http://phish.in/api/v1/shows.json?sort_dir=desc&sort_attr=date&per_page=" + strconv.Itoa(count))
	if err != nil {
		panic(err.Error())
	}

	body, err := ioutil.ReadAll(res.Body)
	if err != nil {
		panic(err.Error())
	}

	var s = new(Show)
	err = json.Unmarshal(body, &s)
	if err != nil {
		fmt.Println("whoops:", err)
	}

	for show := range s.Data {
		id := s.Data[show].Id
		date := s.Data[show].Date
		venue := s.Data[show].Venue

		fmt.Println(id)
		fmt.Println(date)
		fmt.Println(venue)

		var msg = new(GcmData)
		msg.MediaId = strconv.Itoa(int(id))
		fmt.Println(msg.MediaId)
		msg.Title = venue
		msg.SubTitle = date

		var gcm = new(GcmMessage)
		gcm.To = "/topics/global"
		gcm.Data = *msg

		body, err := json.Marshal(&gcm)
		if err != nil {
			fmt.Println("whoops:", err)
		}

		sendGsm(body)
	}
}

func getYears() ([]Year, error) {

	res, err := http.Get("http://phish.in/api/v1/years.json?include_show_counts=true&sort_dir=desc")
	if err != nil {
		panic(err.Error())
	}

	body, err := ioutil.ReadAll(res.Body)
	if err != nil {
		panic(err.Error())
	}

	var s = new(APIResponse)
	err = json.Unmarshal(body, &s)
	if err != nil {
		fmt.Println("whoops:", err)
	}
	return s.Years, err
}
